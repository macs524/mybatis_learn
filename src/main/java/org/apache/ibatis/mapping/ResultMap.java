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
package org.apache.ibatis.mapping;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.Jdk;
import org.apache.ibatis.reflection.ParamNameUtil;
import org.apache.ibatis.session.Configuration;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.*;

/**
 *
 * @author Clinton Begin
 */
public class ResultMap {
    private Configuration configuration;

    private String id;
    private Class<?> type;
    private List<ResultMapping> resultMappings;
    private List<ResultMapping> idResultMappings;
    private List<ResultMapping> constructorResultMappings;
    private List<ResultMapping> propertyResultMappings;
    private Set<String> mappedColumns;
    private Set<String> mappedProperties;
    private Discriminator discriminator;
    private boolean hasNestedResultMaps;
    private boolean hasNestedQueries;
    private Boolean autoMapping;

    private ResultMap() {
    }

    public static class Builder {
        private static final Log log = LogFactory.getLog(Builder.class);

        private ResultMap resultMap = new ResultMap();

        public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings) {
            this(configuration, id, type, resultMappings, null);
        }


        /**
         * Builder 构造
         * 当我们在sql语句的定义使用resultType时,就会调用到这个方法.
         *
         * @param configuration 配置对象,这个不用说
         * @param id 语句ID,带命名空间
         * @param type 类型,即返回值类型
         * @param resultMappings 参数映射
         * @param autoMapping 是否自动映射
         */
        public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings, Boolean autoMapping) {
            resultMap.configuration = configuration;
            resultMap.id = id;
            resultMap.type = type;
            resultMap.resultMappings = resultMappings;
            resultMap.autoMapping = autoMapping;
        }

        public Builder discriminator(Discriminator discriminator) {
            resultMap.discriminator = discriminator;
            return this;
        }

        public Class<?> type() {
            return resultMap.type;
        }

        /**
         * resultMap的终极构造
         * @return resultMap
         */
        public ResultMap build() {
            if (resultMap.id == null) {
                throw new IllegalArgumentException("ResultMaps must have an id");
            }
            resultMap.mappedColumns = new HashSet<String>();
            resultMap.mappedProperties = new HashSet<String>();
            resultMap.idResultMappings = new ArrayList<ResultMapping>();
            resultMap.constructorResultMappings = new ArrayList<ResultMapping>();
            resultMap.propertyResultMappings = new ArrayList<ResultMapping>();

            final List<String> constructorArgNames = new ArrayList<String>();

            //1. 处理每一个子mapping. 对于resultType转map来说, resultMappings是空的.
            for (ResultMapping resultMapping : resultMap.resultMappings) {
                resultMap.hasNestedQueries = resultMap.hasNestedQueries || resultMapping.getNestedQueryId() != null;
                resultMap.hasNestedResultMaps = resultMap.hasNestedResultMaps || (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null);
                final String column = resultMapping.getColumn();
                if (column != null) {
                    resultMap.mappedColumns.add(column.toUpperCase(Locale.ENGLISH)); //映射的列
                } else if (resultMapping.isCompositeResult()) {
                    for (ResultMapping compositeResultMapping : resultMapping.getComposites()) {
                        final String compositeColumn = compositeResultMapping.getColumn();
                        if (compositeColumn != null) {
                            resultMap.mappedColumns.add(compositeColumn.toUpperCase(Locale.ENGLISH));
                        }
                    }
                }

                //映射的属性
                final String property = resultMapping.getProperty();
                if (property != null) {
                    resultMap.mappedProperties.add(property);
                }

                //mapping 分类
                if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                    resultMap.constructorResultMappings.add(resultMapping); //构造函数的mapping
                    if (resultMapping.getProperty() != null) {
                        constructorArgNames.add(resultMapping.getProperty());
                    }
                } else {
                    resultMap.propertyResultMappings.add(resultMapping); //普通属性集的mapping
                }
                if (resultMapping.getFlags().contains(ResultFlag.ID)) {
                    resultMap.idResultMappings.add(resultMapping); //id mapping.
                }
            }


            if (resultMap.idResultMappings.isEmpty()) {
                //如果idmapping没有,则取所有.
                resultMap.idResultMappings.addAll(resultMap.resultMappings);
            }


            if (!constructorArgNames.isEmpty()) {
                final List<String> actualArgNames = argNamesOfMatchingConstructor(constructorArgNames);
                if (actualArgNames == null) {
                    throw new BuilderException("Error in result map '" + resultMap.id
                            + "'. Failed to find a constructor in '"
                            + resultMap.getType().getName() + "' by arg names " + constructorArgNames
                            + ". There might be more info in debug log.");
                }
                // 并根据其在类中定义的参数顺序,对mapping进行排序. 这个没毛病, 所以这说明是支持乱序的.
                Collections.sort(resultMap.constructorResultMappings, new Comparator<ResultMapping>() {
                    @Override
                    public int compare(ResultMapping o1, ResultMapping o2) {
                        int paramIdx1 = actualArgNames.indexOf(o1.getProperty());
                        int paramIdx2 = actualArgNames.indexOf(o2.getProperty());
                        return paramIdx1 - paramIdx2;
                    }
                });
            }
            // lock down collections, 使之不可修改
            resultMap.resultMappings = Collections.unmodifiableList(resultMap.resultMappings);
            resultMap.idResultMappings = Collections.unmodifiableList(resultMap.idResultMappings);
            resultMap.constructorResultMappings = Collections.unmodifiableList(resultMap.constructorResultMappings);
            resultMap.propertyResultMappings = Collections.unmodifiableList(resultMap.propertyResultMappings);
            resultMap.mappedColumns = Collections.unmodifiableSet(resultMap.mappedColumns);
            return resultMap;
        }

        //根据构造函数的属性去查找原对象的反射内容,找到一个合适的构造函数,将其参数列表返回回来
        private List<String> argNamesOfMatchingConstructor(List<String> constructorArgNames) {
            Constructor<?>[] constructors = resultMap.type.getDeclaredConstructors();
            for (Constructor<?> constructor : constructors) {
                Class<?>[] paramTypes = constructor.getParameterTypes();
                if (constructorArgNames.size() == paramTypes.length) {
                    List<String> paramNames = getArgNames(constructor);
                    //获取参数名.
                    if (constructorArgNames.containsAll(paramNames)
                            && argTypesMatch(constructorArgNames, paramTypes, paramNames)) {
                        return paramNames;
                    }
                }
            }
            return null;
        }

        /**
         * 对比类型是否匹配
         * @param constructorArgNames XML中定义的参数列表
         * @param paramTypes 实际参数类型
         * @param paramNames 实际参数名
         * @return
         */
        private boolean argTypesMatch(final List<String> constructorArgNames,
                                      Class<?>[] paramTypes, List<String> paramNames) {
            for (int i = 0; i < constructorArgNames.size(); i++) {
                //因为不要求顺序一样,所以定义的参数列表顺序可能和实际的参数列表顺序不一致,那么找类型要以实际参数的为准
                //所以这里有一个转换
                //[id, name] 和 [name, id] 被认为是相同的
                // 那么查找id的类型时,要分三步走 1)根据定义的参数找到实际参数,并找到其位置下标
                // 2)根据那个下标找对应位置的参数类型
                // 3) 从resultMapping中找定义的参数类型
                // 4) 判断定义的参数类型与实际的是否一致
                Class<?> actualType = paramTypes[paramNames.indexOf(constructorArgNames.get(i))];
                Class<?> specifiedType = resultMap.constructorResultMappings.get(i).getJavaType();

                // 5) 通常,这应该是一致的
                if (!actualType.equals(specifiedType)) {
                    if (log.isDebugEnabled()) {
                        log.debug("While building result map '" + resultMap.id
                                + "', found a constructor with arg names " + constructorArgNames
                                + ", but the type of '" + constructorArgNames.get(i)
                                + "' did not match. Specified: [" + specifiedType.getName() + "] Declared: ["
                                + actualType.getName() + "]");
                    }
                    return false;
                }
            }
            return true;
        }

        /**
         * 获取这个构造函数的参数列表
         * @param constructor
         * @return
         */
        private List<String> getArgNames(Constructor<?> constructor) {
            if (resultMap.configuration.isUseActualParamName() && Jdk.parameterExists) {
                return ParamNameUtil.getParamNames(constructor);
            } else {
                List<String> paramNames = new ArrayList<String>();
                final Annotation[][] paramAnnotations = constructor.getParameterAnnotations();

                int paramCount = paramAnnotations.length;

                //遍历其参数信息
                for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
                    String name = null;
                    for (Annotation annotation : paramAnnotations[paramIndex]) {
                        if (annotation instanceof Param) {
                            name = ((Param) annotation).value();
                            break;
                        }
                    }
                    //参数名称要么是@Param注解指定的,要么是arg1..argn.
                    paramNames.add(name != null ? name : "arg" + paramIndex);
                }
                return paramNames;
            }
        }
    }

    public String getId() {
        return id;
    }

    public boolean hasNestedResultMaps() {
        return hasNestedResultMaps;
    }

    public boolean hasNestedQueries() {
        return hasNestedQueries;
    }

    public Class<?> getType() {
        return type;
    }

    public List<ResultMapping> getResultMappings() {
        return resultMappings;
    }

    public List<ResultMapping> getConstructorResultMappings() {
        return constructorResultMappings;
    }

    public List<ResultMapping> getPropertyResultMappings() {
        return propertyResultMappings;
    }

    public List<ResultMapping> getIdResultMappings() {
        return idResultMappings;
    }

    public Set<String> getMappedColumns() {
        return mappedColumns;
    }

    public Set<String> getMappedProperties() {
        return mappedProperties;
    }

    public Discriminator getDiscriminator() {
        return discriminator;
    }

    public void forceNestedResultMaps() {
        hasNestedResultMaps = true;
    }

    public Boolean getAutoMapping() {
        return autoMapping;
    }

}
