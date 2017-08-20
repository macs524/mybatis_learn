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

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import java.util.*;

/**
 * @author Clinton Begin
 */
public class MapperBuilderAssistant extends BaseBuilder {

    private String currentNamespace;
    private final String resource;
    private Cache currentCache;
    private boolean unresolvedCacheRef; // issue #676

    /**
     * 辅助类,把一些功能实现放到这里.
     * @param configuration 配置变量
     * @param resource 资源名? 这个最终会被用来放在MappedStatement中作为其属性之一.
     */
    public MapperBuilderAssistant(Configuration configuration, String resource) {
        super(configuration);
        ErrorContext.instance().resource(resource);
        this.resource = resource;
    }

    public String getCurrentNamespace() {
        return currentNamespace;
    }

    public void setCurrentNamespace(String currentNamespace) {
        if (currentNamespace == null) {
            throw new BuilderException("The mapper element requires a namespace attribute to be specified.");
        }

        if (this.currentNamespace != null && !this.currentNamespace.equals(currentNamespace)) {
            throw new BuilderException("Wrong namespace. Expected '"
                    + this.currentNamespace + "' but found '" + currentNamespace + "'.");
        }

        this.currentNamespace = currentNamespace;
    }

    /**
     * 补充当前的命名空间, 一般是ID用的多
     *
     * 什么样的情况下, isReference 在调用时会传递true呢,
     *
     * 一般是属性值为引用时, 比如说resultMap属性,这个可能会引用到另一个Mapper的定义, 当然也可能不是
     *
     * 所以这种情况下,你必须允许这个属性值可以有".", 那么如果没有".",  我们则认为是属于当前命名空间的
     * 把当前命名空间的前缀加上.
     *
     * 如果属性值不会是引用,只能在本Mapper里定义的话, 比如语句的id. 那么调用的时候传false
     * 为了避免重复加命名空间, 判断属性是否以当前命名空间开头,如果是的话, 说明已经加过,不处理,不是,则加上.
     * 这种情况下,不允许属性值有"."
     *
     * 从调用的情况上来看, 允许引用其它mapper的属性有如下:
     * 1) resultMap
     * 2) extend
     * 3) parameterMap
     * 4) select
     * 5) refid
     *
     * 其中parameterMap已经不用了, 所以主要就是那4个, 至于为什么是这四个也是很好理解的,就不多说了.
     *
     * 这个方法终于理解了.
     * @param base  基本属性
     * @param isReference 是否引用
     * @return 返回补充后的结果
     */
    public String applyCurrentNamespace(String base, boolean isReference) {
        if (base == null) {
            return null;
        }

        if (isReference) {
            // is it qualified with any namespace yet?
            if (base.contains(".")) {
                return base;
            }
        } else {
            // is it qualified with this namespace yet?
            if (base.startsWith(currentNamespace + ".")) {
                return base;
            }
            if (base.contains(".")) {
                throw new BuilderException("Dots are not allowed in element names, please remove it from " + base);
            }
        }
        return currentNamespace + "." + base;
    }

    /**
     * 使用引用命名空间里的cache
     * @param namespace
     * @return
     */
    public Cache useCacheRef(String namespace) {
        if (namespace == null) {
            throw new BuilderException("cache-ref element requires a namespace attribute.");
        }
        try {
            unresolvedCacheRef = true;

            //每个cache在configuration中都有存储,所以直接使用指定的命名空间查询即可
            Cache cache = configuration.getCache(namespace); //这里并没有相互引用.
            if (cache == null) {
                //如果这个缓存不存在,那么这也是有可能的,因为这涉及到文件解析的先后顺序.
                // 很可能被引用的命名空间对应的文件还没有被解析.
                throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.");
            }
            currentCache = cache; //设置当前缓存
            unresolvedCacheRef = false; //解析完成
            return cache;
        } catch (IllegalArgumentException e) {
            throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.", e);
        }
    }

    public Cache useNewCache(Class<? extends Cache> typeClass,
                             Class<? extends Cache> evictionClass,
                             Long flushInterval,
                             Integer size,
                             boolean readWrite,
                             boolean blocking,
                             Properties props) {

        //以构造器的模式创建一个缓存对象.
        Cache cache = new CacheBuilder(currentNamespace)
                .implementation(valueOrDefault(typeClass, PerpetualCache.class))
                .addDecorator(valueOrDefault(evictionClass, LruCache.class))
                .clearInterval(flushInterval)
                .size(size)
                .readWrite(readWrite)
                .blocking(blocking)
                .properties(props)
                .build();

        //在全局配置中加上这个缓存
        configuration.addCache(cache);
        currentCache = cache; //使用当前缓存, 所以这个解析一定要在cache-ref之后 ,可以替换.
        return cache;
    }

    public ParameterMap addParameterMap(String id, Class<?> parameterClass, List<ParameterMapping> parameterMappings) {
        id = applyCurrentNamespace(id, false);
        ParameterMap parameterMap = new ParameterMap.Builder(configuration, id, parameterClass, parameterMappings).build();
        configuration.addParameterMap(parameterMap);
        return parameterMap;
    }

    /**
     * 构造一个参数映射
     * @param parameterType 参数类型
     * @param property 属性
     * @param javaType JAVA类型
     * @param jdbcType JDBC类型
     * @param resultMap 结果集
     * @param parameterMode 参数模式
     * @param typeHandler 参数类型转换器
     * @param numericScale 精度?
     * @return 生成好的参数映射
     */
    public ParameterMapping buildParameterMapping(
            Class<?> parameterType,
            String property,
            Class<?> javaType,
            JdbcType jdbcType,
            String resultMap,
            ParameterMode parameterMode,
            Class<? extends TypeHandler<?>> typeHandler,
            Integer numericScale) {

        //加上当前的别名引用
        resultMap = applyCurrentNamespace(resultMap, true);

        Class<?> javaTypeClass = resolveParameterJavaType(parameterType, property, javaType, jdbcType);
        TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);

        return new ParameterMapping.Builder(configuration, property, javaTypeClass)
                .jdbcType(jdbcType)
                .resultMapId(resultMap)
                .mode(parameterMode)
                .numericScale(numericScale)
                .typeHandler(typeHandlerInstance)
                .build();
    }

    /**
     * 添加resultMap, 最终的处理
     * @param id 表示这个resultMap的惟一索引
     * @param type 类型
     * @param extend 继承自
     * @param discriminator 鉴别器
     * @param resultMappings map中的各子节点组成的resultMapping
     * @param autoMapping 是否自动映射
     * @return 最终的map
     */
    public ResultMap addResultMap(
            String id,
            Class<?> type,
            String extend,
            Discriminator discriminator,
            List<ResultMapping> resultMappings,
            Boolean autoMapping) {
        id = applyCurrentNamespace(id, false);
        extend = applyCurrentNamespace(extend, true);

        if (extend != null) {

            //如果待继承的还没有解析,则先将其置为未完成
            if (!configuration.hasResultMap(extend)) {
                throw new IncompleteElementException("Could not find a parent resultmap with id '" + extend + "'");
            }
            ResultMap resultMap = configuration.getResultMap(extend); // 父类
            List<ResultMapping> extendedResultMappings = new ArrayList<ResultMapping>(resultMap.getResultMappings());
            extendedResultMappings.removeAll(resultMappings); //这是扩展那一部分
            // Remove parent constructor if this resultMap declares a constructor.
            boolean declaresConstructor = false;
            for (ResultMapping resultMapping : resultMappings) {
                if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                    declaresConstructor = true;
                    break;
                }
            }


            if (declaresConstructor) {

                //定义了构造函数, 则移除父类的构造函数.
                Iterator<ResultMapping> extendedResultMappingsIter = extendedResultMappings.iterator();
                while (extendedResultMappingsIter.hasNext()) {
                    if (extendedResultMappingsIter.next().getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                        extendedResultMappingsIter.remove();
                    }
                }
            }
            resultMappings.addAll(extendedResultMappings);
        }


        ResultMap resultMap = new ResultMap.Builder(configuration, id, type, resultMappings, autoMapping)
                .discriminator(discriminator)
                .build();


        configuration.addResultMap(resultMap);
        return resultMap;
    }

    public Discriminator buildDiscriminator(
            Class<?> resultType,
            String column,
            Class<?> javaType,
            JdbcType jdbcType,
            Class<? extends TypeHandler<?>> typeHandler,
            Map<String, String> discriminatorMap) {

        //1. 根据参数构造一个resultMapping
        ResultMapping resultMapping = buildResultMapping(
                resultType,
                null,
                column,
                javaType,
                jdbcType,
                null,
                null,
                null,
                null,
                typeHandler,
                new ArrayList<ResultFlag>(),
                null,
                null,
                false);

        //将原来的resultMap的ID加上namespace.
        Map<String, String> namespaceDiscriminatorMap = new HashMap<String, String>();
        for (Map.Entry<String, String> e : discriminatorMap.entrySet()) {
            String resultMap = e.getValue(); //1, key加前缀
            resultMap = applyCurrentNamespace(resultMap, true); //2. map
            namespaceDiscriminatorMap.put(e.getKey(), resultMap);

        }

        //生成一个鉴别器
        return new Discriminator.Builder(configuration, resultMapping, namespaceDiscriminatorMap).build();
    }

    public MappedStatement addMappedStatement(
            String id,
            SqlSource sqlSource,
            StatementType statementType,
            SqlCommandType sqlCommandType,
            Integer fetchSize,
            Integer timeout,
            String parameterMap,
            Class<?> parameterType,
            String resultMap,
            Class<?> resultType,
            ResultSetType resultSetType,
            boolean flushCache,
            boolean useCache,
            boolean resultOrdered,
            KeyGenerator keyGenerator,
            String keyProperty,
            String keyColumn,
            String databaseId,
            LanguageDriver lang,
            String resultSets) {

        if (unresolvedCacheRef) {
            throw new IncompleteElementException("Cache-ref not yet resolved");
        }

        id = applyCurrentNamespace(id, false);
        boolean isSelect = sqlCommandType == SqlCommandType.SELECT;

        MappedStatement.Builder statementBuilder = new MappedStatement.Builder(configuration, id, sqlSource, sqlCommandType)
                .resource(resource)
                .fetchSize(fetchSize)
                .timeout(timeout)
                .statementType(statementType)
                .keyGenerator(keyGenerator)
                .keyProperty(keyProperty)
                .keyColumn(keyColumn)
                .databaseId(databaseId)
                .lang(lang)
                .resultOrdered(resultOrdered)
                .resultSets(resultSets)
                .resultMaps(getStatementResultMaps(resultMap, resultType, id))
                .resultSetType(resultSetType)
                .flushCacheRequired(valueOrDefault(flushCache, !isSelect)) //是否清除缓存，由flushCache来决定，默认是要清除缓存的。（只要不是select)
                .useCache(valueOrDefault(useCache, isSelect))
                .cache(currentCache);

        //虽然我们自己不再定义parameterType, 但最终parameterType是要转化成ParameterMap的.
        ParameterMap statementParameterMap = getStatementParameterMap(parameterMap, parameterType, id);

        if (statementParameterMap != null) {
            statementBuilder.parameterMap(statementParameterMap);
        }

        MappedStatement statement = statementBuilder.build();
        configuration.addMappedStatement(statement); //最终是以key-value的形式添加到configure中去.
        return statement;
    }

    private <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    /**
     * 解析ParameterMap
     * @param parameterMapName 引用的ParameterMap, 这个已几乎要弃用了,可以认为是NULL
     * @param parameterTypeClass 节点中指定的参数类型,即 parameterType
     * @param statementId 语句ID, 这里的ID是加上了命名空间之后的ID
     * @return ParameterMap
     */
    private ParameterMap getStatementParameterMap(
            String parameterMapName,
            Class<?> parameterTypeClass,
            String statementId) {

        parameterMapName = applyCurrentNamespace(parameterMapName, true);
        ParameterMap parameterMap = null;

        if (parameterMapName != null) {
            //1. 根据名称查找已定义的parameterMap, 这个可以忽略
            try {
                parameterMap = configuration.getParameterMap(parameterMapName);
            } catch (IllegalArgumentException e) {
                throw new IncompleteElementException("Could not find parameter map " + parameterMapName, e);
            }
        } else if (parameterTypeClass != null) {

            // 根据参数类型进行构造, 这是当前推荐的方式, 而且这两者不能同时使用,优先取map
            // 这种情况下是没有参数映射的,所以参数映射是空集合.
            List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();

            // 构造Map, 从实现上来看这个Map很简单,只是封装了ID和type, 没有参数映射.
            // 我想之所以被弃用,是因为团队觉得参数映射意义不大吧. 或者是可以以别的方式实现.
            parameterMap = new ParameterMap.Builder(
                    configuration,
                    statementId + "-Inline",
                    parameterTypeClass,
                    parameterMappings).build();
        }
        return parameterMap;
    }

    /**
     * 构造一个resultMap的list.
     * @param resultMap 节点中的resultMap属性
     * @param resultType 节点的resultType属性
     * @param statementId 语句ID
     * @return 得到一个resultMap节点
     */
    private List<ResultMap> getStatementResultMaps(
            String resultMap, //resultMap名称
            Class<?> resultType,
            String statementId) {
        resultMap = applyCurrentNamespace(resultMap, true);

        List<ResultMap> resultMaps = new ArrayList<ResultMap>();
        if (resultMap != null) {
            //如果直接指定了resultMap当然就使用resultMap了.
            String[] resultMapNames = resultMap.split(",");
            for (String resultMapName : resultMapNames) {
                try {
                    resultMaps.add(configuration.getResultMap(resultMapName.trim()));
                } catch (IllegalArgumentException e) {
                    throw new IncompleteElementException("Could not find result map " + resultMapName, e);
                }
            }
        } else if (resultType != null) {
            //否则就把resultType转换为ResultMap. 只是肯定没有mapping.
            //嬠没有指定ID就是inline
            ResultMap inlineResultMap = new ResultMap.Builder(
                    configuration,
                    statementId + "-Inline",
                    resultType,
                    new ArrayList<ResultMapping>(),
                    null).build();

            // 列表中内容只有一个
            resultMaps.add(inlineResultMap);
        }
        return resultMaps;
    }

    /**
     * 构造一个ResultMapping
     *
     * @param resultType resultMap的类型
     * @param property 该节点对应的Java POJO的属性名
     * @param column 列名, 和property应该是一一对应的
     * @param javaType java类型
     * @param jdbcType jdbc类型
     * @param nestedSelect select子句
     * @param nestedResultMap 内嵌的resultMap
     * @param notNullColumn 非NULL列
     * @param columnPrefix 列前缀
     * @param typeHandler 类型转换器
     * @param flags 节点标签
     * @param resultSet resultSet 结果集
     * @param foreignColumn 外键,
     * @param lazy 是否延迟
     * @return 对应的一个对象
     */
    public ResultMapping buildResultMapping(
            Class<?> resultType,
            String property,
            String column,
            Class<?> javaType,
            JdbcType jdbcType,
            String nestedSelect,
            String nestedResultMap,
            String notNullColumn,
            String columnPrefix,
            Class<? extends TypeHandler<?>> typeHandler,
            List<ResultFlag> flags,
            String resultSet,
            String foreignColumn,
            boolean lazy) {

        //经过处理, javaType至少是Object.class
        Class<?> javaTypeClass = resolveResultJavaType(resultType, property, javaType);

        //如果定义了则取定义的值,如果没有定义,则需要创建一个.
        // 但如果typeHandler为NULL的话,则typeHandlerInstance也肯定是为NULL的,直接后面就不处理了.
        TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);

        List<ResultMapping> composites = parseCompositeColumnName(column);

        //最终,我们生成了一个ResultMapping.
        return new ResultMapping.Builder(configuration, property, column, javaTypeClass)
                .jdbcType(jdbcType)
                .nestedQueryId(applyCurrentNamespace(nestedSelect, true)) //加上了命名空间
                .nestedResultMapId(applyCurrentNamespace(nestedResultMap, true))
                .resultSet(resultSet)
                .typeHandler(typeHandlerInstance)
                .flags(flags == null ? new ArrayList<ResultFlag>() : flags)
                .composites(composites)
                .notNullColumns(parseMultipleColumnNames(notNullColumn))
                .columnPrefix(columnPrefix)
                .foreignColumn(foreignColumn)
                .lazy(lazy)
                .build();
    }

    private Set<String> parseMultipleColumnNames(String columnName) {
        Set<String> columns = new HashSet<String>();
        if (columnName != null) {
            if (columnName.indexOf(',') > -1) {
                StringTokenizer parser = new StringTokenizer(columnName, "{}, ", false);
                while (parser.hasMoreTokens()) {
                    String column = parser.nextToken();
                    columns.add(column);
                }
            } else {
                columns.add(columnName);
            }
        }
        return columns;
    }

    /**
     * 处理多列的情况,这个我们暂时不分析.
     * @param columnName
     * @return
     */
    private List<ResultMapping> parseCompositeColumnName(String columnName) {
        List<ResultMapping> composites = new ArrayList<ResultMapping>();
        if (columnName != null && (columnName.indexOf('=') > -1 || columnName.indexOf(',') > -1)) {
            StringTokenizer parser = new StringTokenizer(columnName, "{}=, ", false);
            while (parser.hasMoreTokens()) {
                String property = parser.nextToken();
                String column = parser.nextToken();
                ResultMapping complexResultMapping = new ResultMapping.Builder(
                        configuration, property, column, configuration.getTypeHandlerRegistry().getUnknownTypeHandler()).build();
                composites.add(complexResultMapping);
            }
        }
        return composites;
    }

    /**
     * 进一步解析这个节点对应的JAVA类型
     * @param resultType JAVA POJO 类
     * @param property  JAVA POJO 属性
     * @param javaType 配置的JAVA类型
     * @return 最终的JAVA类型
     */
    private Class<?> resolveResultJavaType(Class<?> resultType, String property, Class<?> javaType) {

        //如果没有配置,则通过反射的方式去解析出javaType.
        if (javaType == null && property != null) {
            try {
                MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
                javaType = metaResultType.getSetterType(property);
            } catch (Exception e) {
                //ignore, following null check statement will deal with the situation
            }
        }
        if (javaType == null) {
            //如果没找到,就为Object吧
            javaType = Object.class;
        }

        return javaType;
    }

    /**
     * 根据诸多的入参来解析JavaType,
     * 其实如果javaType不为NULL,则根本不需要解析
     *
     * @param resultType 参数类型
     * @param property 属性
     * @param javaType javaType
     * @param jdbcType jdbcType
     * @return
     */
    private Class<?> resolveParameterJavaType(Class<?> resultType, String property, Class<?> javaType, JdbcType jdbcType) {
        if (javaType == null) {
            if (JdbcType.CURSOR.equals(jdbcType)) {
                //如果JDBC是游标,则java是ResultSet
                javaType = java.sql.ResultSet.class;
            } else if (Map.class.isAssignableFrom(resultType)) {
                //如果参数类型是Map, 则javaType应为Object
                javaType = Object.class;
            } else {
                //通过反射的方式来根据字段取得其类型,所以能直接设置值就直接点,这样能提高效率.
                MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
                javaType = metaResultType.getGetterType(property);
            }
        }
        if (javaType == null) {
            javaType = Object.class;
        }
        return javaType;
    }

    public LanguageDriver getLanguageDriver(Class<?> langClass) {
        if (langClass != null) {
            configuration.getLanguageRegistry().register(langClass);
        } else {
            langClass = configuration.getLanguageRegistry().getDefaultDriverClass();
        }
        return configuration.getLanguageRegistry().getDriver(langClass);
    }

}
