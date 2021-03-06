/**
 * Copyright 2009-2017 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.builder;

import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL解析器，主要来解析SQL中的#{}？
 *
 * @author Clinton Begin
 */
public class SqlSourceBuilder extends BaseBuilder {

    private static final String parameterProperties = "javaType,jdbcType,mode,numericScale,resultMap,typeHandler,jdbcTypeName";

    public SqlSourceBuilder(Configuration configuration) {
        super(configuration);
    }

    /**
     * 解析处理， 无论是动态SQL，还是静态SQL，都需要调用这个方法来处理
     * @param originalSql 原始SQL
     * @param parameterType 参数类型
     * @param additionalParameters 附加参数， 这有可能是一个空参数，比如以RawSqlSource来说
     * @return 解析结果, 最终得到一个静态的SqlSource
     */
    public SqlSource parse(String originalSql, Class<?> parameterType, Map<String, Object> additionalParameters) {

        //1. 构造一个参数解析器，用于处理变量表达式#{}
        ParameterMappingTokenHandler handler = new ParameterMappingTokenHandler(configuration, parameterType, additionalParameters);

        //2. 负责找到这样的表达式
        GenericTokenParser parser = new GenericTokenParser("#{", "}", handler);

        //3. 执行解后的操作， 这个SQL中的#{}将不复存在，而是会被替换成?， 那么这个SQL是可以直接被执行的。
        String sql = parser.parse(originalSql);

        //4. 返回一个静态的SqlSource
        return new StaticSqlSource(configuration, sql, handler.getParameterMappings());
    }

    /**
     * 静态内部类，其主要作用在于解析SQL中的变量
     * */
    private static class ParameterMappingTokenHandler extends BaseBuilder implements TokenHandler {

        private List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
        private Class<?> parameterType;
        private MetaObject metaParameters;

        public ParameterMappingTokenHandler(Configuration configuration, Class<?> parameterType, Map<String, Object> additionalParameters) {
            super(configuration);
            this.parameterType = parameterType;
            this.metaParameters = configuration.newMetaObject(additionalParameters);
        }

        public List<ParameterMapping> getParameterMappings() {
            return parameterMappings;
        }

        @Override
        public String handleToken(String content) {
            //这里的content应该就是#{}里的内容,很可能只是一个变量名
            //比如#{id}, 也有可能还包含了其它信息,比如 #{id, javaType=int, jdbcType=INTEGER}类似的形式.

            //重点是了为解析参数，并将解析后的添加到parameterMappings中去。
            parameterMappings.add(buildParameterMapping(content));

            return "?"; //直接返回?
        }

        /**
         * 根据变量名构造出一个ParameterMapping.
         * content就是类似于#{}里的部分，可能是一个简单的#{id}, 也可能是一个复杂的#{id, javaType=xx, jdbcType=xxx} 这种形式
         *
         * 而这个方法的作用，就是把一个文本通过解析，转化为一个ParameterMapping对象
         * @param content 变量名
         * @return 参数映射
         */
        private ParameterMapping buildParameterMapping(String content) {
            //所以这里把#{}里的字符串要转化为Map
            Map<String, String> propertiesMap = parseParameterMapping(content);
            String property = propertiesMap.get("property");

            //计算属性类型
            Class<?> propertyType;
            if (metaParameters.hasGetter(property)) { // issue #448 get type from additional params
                propertyType = metaParameters.getGetterType(property);
            } else if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
                propertyType = parameterType;
            } else if (JdbcType.CURSOR.name().equals(propertiesMap.get("jdbcType"))) {
                propertyType = java.sql.ResultSet.class;
            } else if (property == null || Map.class.isAssignableFrom(parameterType)) {
                propertyType = Object.class;
            } else {
                MetaClass metaClass = MetaClass.forClass(parameterType, configuration.getReflectorFactory());
                if (metaClass.hasGetter(property)) {
                    propertyType = metaClass.getGetterType(property);
                } else {
                    propertyType = Object.class;
                }
            }

            //这一块的代码着实比较复杂, 暂不分析
            ParameterMapping.Builder builder = new ParameterMapping.Builder(configuration, property, propertyType);
            Class<?> javaType = propertyType;
            String typeHandlerAlias = null;
            for (Map.Entry<String, String> entry : propertiesMap.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if ("javaType".equals(name)) {
                    javaType = resolveClass(value);
                    builder.javaType(javaType);
                } else if ("jdbcType".equals(name)) {
                    builder.jdbcType(resolveJdbcType(value));
                } else if ("mode".equals(name)) {
                    builder.mode(resolveParameterMode(value));
                } else if ("numericScale".equals(name)) {
                    builder.numericScale(Integer.valueOf(value));
                } else if ("resultMap".equals(name)) {
                    builder.resultMapId(value);
                } else if ("typeHandler".equals(name)) {
                    typeHandlerAlias = value;
                } else if ("jdbcTypeName".equals(name)) {
                    builder.jdbcTypeName(value);
                } else if ("property".equals(name)) {
                    // Do Nothing
                } else if ("expression".equals(name)) {
                    throw new BuilderException("Expression based parameters are not supported yet");
                } else {
                    throw new BuilderException("An invalid property '" + name + "' was found in mapping #{" + content + "}.  Valid properties are " + parameterProperties);
                }
            }
            if (typeHandlerAlias != null) {
                builder.typeHandler(resolveTypeHandler(javaType, typeHandlerAlias));
            }
            return builder.build();
        }

        private Map<String, String> parseParameterMapping(String content) {
            try {
                return new ParameterExpression(content);
            } catch (BuilderException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new BuilderException("Parsing error was found in mapping #{" + content + "}.  Check syntax #{property|(expression), var1=value1, var2=value2, ...} ", ex);
            }
        }
    }

}
