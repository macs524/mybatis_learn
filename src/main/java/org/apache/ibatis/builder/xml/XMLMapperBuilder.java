/**
 *    Copyright 2009-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import org.apache.ibatis.builder.*;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import java.io.InputStream;
import java.io.Reader;
import java.util.*;

/**
 * @author Clinton Begin
 */
public class XMLMapperBuilder extends BaseBuilder {

  private final XPathParser parser;
  private final MapperBuilderAssistant builderAssistant;
  private final Map<String, XNode> sqlFragments;
  private final String resource;

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

    /**
     * 构造Mapper解析器, 跟XMLConfigBuilder类似,却又比其复杂得多.
     * @param inputStream 待解析Mapper文件对应的文件流
     * @param configuration 已有的配置对象
     * @param resource 文件全路径名
     * @param sqlFragments 一个Map, 存放SQL及其对应节点的映射关系.
     */
  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

    /**
     * 终极构造方法
     * @param parser 解析器, 和XMLConfigMapper一样, 当引用时, 配置文件已经被解析成了Document对象
     * @param configuration 配置
     * @param resource 文件名
     * @param sqlFragments SQL节点Map
     */
  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
  }

  public void parse() {

      //判断资源是否加载过
    if (!configuration.isResourceLoaded(resource)) {

        //没有则进行解析,也就是说, 多个同名的资源文件,虽然在这里不报错,但也不会解析多次
        //终极解析开始了,先从Mapper开始
      configurationElement(parser.evalNode("/mapper"));

      configuration.addLoadedResource(resource); //表示资源已经加载.
      bindMapperForNamespace();
        //那么,至此,对于Mybatis的源码分析, 至少配置解析这一段的主体线路就算是比较清楚了.
        //其来龙去脉有了个清晰的认识,但是有些地方还是需要测试.
    }

      //每加载一个mapper文件,就对原来没解析成功的再次解析
    parsePendingResultMaps(); //resultMap
    parsePendingCacheRefs(); //缓存引用
    parsePendingStatements(); //阻塞的语句
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }

    /**
     * 真正解析Mapper文件的代码.
     *
     * 和XmlConfigBuilder不同的是,对于每一个Mapper,系统都会创建一个XmlMapperBuilder
     *
     * 而这多个Builder之间,除了共用Configuration之外,其它的都是相互独立的.
     * 所以它们相互之间并没有多少关联关系.
     * @param context 待解析的mapper节点
     */
  private void configurationElement(XNode context) {
    try {
        //1. 对于Mapper节点来说,属性namespace是最为重要的, 必须获取了之后,设置为当前namespace.
      String namespace = context.getStringAttribute("namespace");
      if (namespace == null || namespace.equals("")) {
        throw new BuilderException("Mapper's namespace cannot be empty");
      }
      builderAssistant.setCurrentNamespace(namespace); //这个一般情况下只会设置一次.

        //2. 解析cache-ref节点
      cacheRefElement(context.evalNode("cache-ref"));
        //3. 解析cache
      cacheElement(context.evalNode("cache"));
        //4. parameterMap.
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
        //5. 解析resultMap, 这个比较重要
      resultMapElements(context.evalNodes("/mapper/resultMap"));
        //6. 解析sql节点
      sqlElement(context.evalNodes("/mapper/sql"));
        //7. 解析四大节点
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));

    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. Cause: " + e, e);
    }
  }

  private void buildStatementFromContext(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    buildStatementFromContext(list, null);
  }

    /**
     * 这个属性终级解析了,也应该是最复杂的了.
     * @param list 待处理的节点,可以是select,update, insert, delete. 没有其它节点了
     * @param requiredDatabaseId 需要的databaseId, 可以认为其就是NULL
     */
  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
        //由于很复杂,所以又单独使用一个Builder来解析,如下.
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
          //如果解析异常,先存起来.
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  private void parsePendingResultMaps() {
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
        }
      }
    }
  }

  private void parsePendingCacheRefs() {
    Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
    synchronized (incompleteCacheRefs) {
      Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolveCacheRef();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Cache ref is still missing a resource...
        }
      }
    }
  }

  private void parsePendingStatements() {
    Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
    synchronized (incompleteStatements) {
      Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().parseStatementNode();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Statement is still missing a resource...
        }
      }
    }
  }

    /**
     * 对于缓存引用节点的解析. 该节点的定义如下:
     *
     *  <cache-ref namespace="org.apache.ibatis.submitted.xml_external_ref.SameIdPetMapper" />
     *
     *  所以主要解析的就是获取其namespace节点
     *
     * @param context
     */
  private void cacheRefElement(XNode context) {
    if (context != null) {

        String namespace = context.getStringAttribute("namespace");

        //1. 用Map建立它们之间的引用关系
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), namespace);
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, namespace);
      try {
        cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
          //如果出现异常,说明解析没有成功,这可能并不是程序问题,而是先后顺序的问题
          //所以,先暂存起来,后续再补充处理.
        configuration.addIncompleteCacheRef(cacheRefResolver);
      }

        //但是这个也可能存在传递引用的情况,到时候再这种情况mybatis 是如何处理的.
    }
  }

    /**
     * 解析缓存节点
     *
     * 通常我们直接使用 <cache /> , 系统会帮我们设置各个默认值 , 当然,我们也可以显示指定.
     *
     * 除了节点本身的属性,还支持设置属性值<property></property>
     *
     * @param context cache
     * @throws Exception
     */
  private void cacheElement(XNode context) throws Exception {
    if (context != null) {

        //1. 缓存类型, 目前来说也只有这一个是具体的实现
      String type = context.getStringAttribute("type", "PERPETUAL");
        //由于使用的是别名,所以不用resolveClass, 直接使用别名查找
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);

        //2. 回收策略,默认LRU
      String eviction = context.getStringAttribute("eviction", "LRU");
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);

        //3. 刷新间隔,这个没有指定默认值(ms)
      Long flushInterval = context.getLongAttribute("flushInterval");
        //4. 大小,这个也没有指定
      Integer size = context.getIntAttribute("size");
        //5. 是否可读写,readOnly取反,默认是可写的.
      boolean readWrite = !context.getBooleanAttribute("readOnly", false);
        //6. 是否分块?这个不清楚,默认为false
      boolean blocking = context.getBooleanAttribute("blocking", false);
        //7. 获取附加属性.
      Properties props = context.getChildrenAsProperties();
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
    }
  }

    /**
     * 解析参数Map节点. 可以有多个,所以要循环检查
     * 这个目前已经不推荐使用了,不过本着读代码的目的,还是看下
     *
     * 一个示例如下:
     *  <parameterMap id="selectAuthor" type="org.apache.ibatis.domain.blog.Author">
            <parameter property="id" />
        </parameterMap>

       所以可以预见,对于这个节点的解析, 主要是解析其id属性和type属性,
       以及其子节点里的其它参数

     * @param list 列表
     * @throws Exception
     */
  private void parameterMapElement(List<XNode> list) throws Exception {
    for (XNode parameterMapNode : list) {
      String id = parameterMapNode.getStringAttribute("id");

        String type = parameterMapNode.getStringAttribute("type");
       Class<?> parameterClass = resolveClass(type); //这个可以是别名


      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
      for (XNode parameterNode : parameterNodes) {

        String property = parameterNode.getStringAttribute("property"); //属性,应该是type所对应类的字段名
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");

        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale"); //精度?

          String mode = parameterNode.getStringAttribute("mode"); //参数模式IN, OUT或者INOUT
        ParameterMode modeEnum = resolveParameterMode(mode);//

          //映射关系三要求,javaType, jdbcType和typeHandler
        Class<?> javaTypeClass = resolveClass(javaType);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);

        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping); //构建出参数集
      }
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  private void resultMapElements(List<XNode> list) throws Exception {
    for (XNode resultMapNode : list) {
      try {
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
    return resultMapElement(resultMapNode, Collections.<ResultMapping> emptyList());
  }

    /**
     * 解析resultMap, 示例节点如下:
     *
     * 	 <resultMap id="selectAuthor" type="org.apache.ibatis.domain.blog.Author">
             <id column="id" property="id" />
             <result property="username" column="username" />
             <result property="password" column="password" />
             <result property="email" column="email" />
             <result property="bio" column="bio" />
             <result property="favouriteSection" column="favourite_section" />
         </resultMap>

     * @param resultMapNode 待处理的节点
     * @param additionalResultMappings 附加参数列表
     * @return
     * @throws Exception
     */
  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings) throws Exception {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());

      //1. 取ID,这个ID我们通常会设置, 不设置则自己生成一个
      String id = resultMapNode.getStringAttribute("id",
        resultMapNode.getValueBasedIdentifier());

      //2. 取TYPE,这个Type通常我们也会设置
    String type = resultMapNode.getStringAttribute("type",
        resultMapNode.getStringAttribute("ofType",
            resultMapNode.getStringAttribute("resultType",
                resultMapNode.getStringAttribute("javaType"))));

      //3. 继承自哪个Map, resultMap是可以有继承性的.
    String extend = resultMapNode.getStringAttribute("extends");
      //4. 是否自动Mapping
      Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");

      //5. 解析resultMap对应的类型
    Class<?> typeClass = resolveClass(type);

    Discriminator discriminator = null;

    List<ResultMapping> resultMappings = new ArrayList<ResultMapping>();
    resultMappings.addAll(additionalResultMappings);
    List<XNode> resultChildren = resultMapNode.getChildren();

    //6 节点本身的参数并不复杂,复杂的是其以拥有的子节点,下面开始解析子节点.
    for (XNode resultChild : resultChildren) {
          //根据名称判断子节点的类型
        if ("constructor".equals(resultChild.getName())) {
              //6.1 节点可能是constructor, 于是解析构造函数
                processConstructorElement(resultChild, typeClass, resultMappings);
        } else if ("discriminator".equals(resultChild.getName())) {
              //6.2 节点可能是过滤器节点, 用单独的方法去处理
              discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
        } else {
            //6.3 其它的单独处理, ResultFlag为一个枚举,可能是ID, 也可能是CONSTRUCTOR
            List<ResultFlag> flags = new ArrayList<ResultFlag>();
            if ("id".equals(resultChild.getName())) {
                flags.add(ResultFlag.ID);
            }

            resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
        }
    }

      //总之,我们最终生成了一个表示resultMapping关系的对象列表.
    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
      return resultMapResolver.resolve();
    } catch (IncompleteElementException  e) {
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

    /**
     * 单独处理constructor节点
     * @param resultChild constructor节点
     * @param resultType 结果类型
     * @param resultMappings 原有的resultMapping
     * @throws Exception
     */
  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    List<XNode> argChildren = resultChild.getChildren();
      //主要是要处理其子节点
    for (XNode argChild : argChildren) {
      List<ResultFlag> flags = new ArrayList<ResultFlag>();
      flags.add(ResultFlag.CONSTRUCTOR); //添加constructor标签,表示这个来自于构造节点
      if ("idArg".equals(argChild.getName())) {
        flags.add(ResultFlag.ID); //如果节点是idArg, 添加ID
      }

      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

    /**
     * 单独处理discriminator节点,  这个节点不是太好理解,一个示例定义如下:
     *
     *
     * <discriminator javaType="int" column="draft">
            <case value="1">
                 由于case 子句没有指定 resultMap, 所以其子节点会被解析成一个resultMap.ID是根据一定的规则生成的.
                 <association property="author" resultMap="joinedAuthor"/>
                 <collection property="comments" resultMap="joinedComment"/>
                 <collection property="tags" resultMap="joinedTag"/>
            </case>
      </discriminator>
     * @param context discriminator节点
     * @param resultType map的类型
     * @param resultMappings 结果mapping
     * @return 解析后生成一个Discriminator
     * @throws Exception
     */
  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType"); //JAVA类
    String jdbcType = context.getStringAttribute("jdbcType");
    String typeHandler = context.getStringAttribute("typeHandler");
    Class<?> javaTypeClass = resolveClass(javaType);
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    Map<String, String> discriminatorMap = new HashMap<String, String>();

      //对于这个节点来说,其子节点都是case子句,所以下面对于case子句进行处理
    for (XNode caseChild : context.getChildren()) {
        //对于case子句来说, 最重要的是就是value和 resultMap了.
      String value = caseChild.getStringAttribute("value");
        //value是肯定有的, 但resultMap不一定会设置,假设简单的情况下, resultMap设置了, 则直接取其值
      String resultMap = caseChild.getStringAttribute("resultMap",
              processNestedResultMappings(caseChild, resultMappings)); //否则还要继续解析.

      discriminatorMap.put(value, resultMap); //总之最终是一个值和MAP名称的映射关系.
    }
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }


  private void sqlElement(List<XNode> list) throws Exception {
    if (configuration.getDatabaseId() != null) {
      sqlElement(list, configuration.getDatabaseId());
    }
    sqlElement(list, null);
  }

    /**
     * 解析sql节点, 有点类似于变量定义
     *
     * 示例:
     * <sql id="xxx">id, role_name, note </sql>
     *
     * 这个方法处理得非常简单,仅仅是把满足条件的节点存储了下来.
     * @param list sql节点列表
     * @param requiredDatabaseId 要求的DB ID
     * @throws Exception
     */
  private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
    for (XNode context : list) {
      String databaseId = context.getStringAttribute("databaseId");
      String id = context.getStringAttribute("id");
      id = builderAssistant.applyCurrentNamespace(id, false);
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
        sqlFragments.put(id, context);
      }
    }
  }
  
  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      if (!requiredDatabaseId.equals(databaseId)) {
        return false;
      }
    } else {
      if (databaseId != null) {
        return false;
      }
      // skip this fragment if there is a previous one with a not null databaseId
      if (this.sqlFragments.containsKey(id)) {
        XNode context = this.sqlFragments.get(id);
        if (context.getStringAttribute("databaseId") != null) {
          return false;
        }
      }
    }
    return true;
  }

    /**
     * 统一解析,那么可能的节点是ID/RESULT/ASSOCIATION/COLLECTION/IDARG/ARG
     *
     * 这个才是解析resultMap下各个result的
     * 并最终生成一个resultMapping
     * @param context 节点,这个当然必须有
     * @param resultType resultMap的类型
     * @param flags 节点标记,可能有ID,还有可能有CONSTRUCTOR
     * @return 转换之后的对象
     * @throws Exception
     */
  private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
    String property;

      //如果是constructor节点, 则属性由name表示, 否则其它由property表示
    if (flags.contains(ResultFlag.CONSTRUCTOR)) {
      property = context.getStringAttribute("name");
    } else {
      property = context.getStringAttribute("property");
    }
      //以下是一些常见的字段
    String column = context.getStringAttribute("column"); //列名, 即数据表中的列名
    String javaType = context.getStringAttribute("javaType"); //JAVA类型
    String jdbcType = context.getStringAttribute("jdbcType"); //JDBC的类型
    String nestedSelect = context.getStringAttribute("select"); //select子句,这个一般在一对多的时候能用到.

      //解析resultMap属性, 这个应该是很少能用到吧,暂时不分析
    String nestedResultMap = context.getStringAttribute("resultMap",
        processNestedResultMappings(context, Collections.<ResultMapping> emptyList()));
      //非空列?
    String notNullColumn = context.getStringAttribute("notNullColumn");
      //列前缀
    String columnPrefix = context.getStringAttribute("columnPrefix");
      //typeHandler, 即类型处理器
    String typeHandler = context.getStringAttribute("typeHandler");
      //resultSet
    String resultSet = context.getStringAttribute("resultSet");
      //外键,这个不常用
    String foreignColumn = context.getStringAttribute("foreignColumn");
      //根据fetchType来决定是否延迟加载, 可以设置lazy, eager或不设置.
    boolean lazy = "lazy".equals(
            context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));

    Class<?> javaTypeClass = resolveClass(javaType); //java类型

      //处理器类型, 如果直接指定了,当然不会为NULL, 也就不用从MAP中查找了
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerClass =
            (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);

      //JDBC类型,也可能为nULL
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);

      //最终的构造方法
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
  }

    //节点是case节点,且无select
  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings) throws Exception {
    if ("association".equals(context.getName())
        || "collection".equals(context.getName())
        || "case".equals(context.getName())) {
      if (context.getStringAttribute("select") == null) {
          //则继续解析.
        ResultMap resultMap = resultMapElement(context, resultMappings);
        return resultMap.getId();
      }
    }
    return null;
  }

    /**
     * 注意这一个方法, 非常重要.
     */
  private void bindMapperForNamespace() {
    String namespace = builderAssistant.getCurrentNamespace();
    if (namespace != null) {
      Class<?> boundType = null;
      try {
        boundType = Resources.classForName(namespace);
      } catch (ClassNotFoundException e) {
        //ignore, bound type is not required
      }
      if (boundType != null) {
          //如果nameSpace对应了一个Mapper, 且没有被解析, 则对这个Mapper进行解析
          // 前缀是加上"namespace:"
        if (!configuration.hasMapper(boundType)) {
          // Spring may not know the real resource name so we set a flag
          // to prevent loading again this resource from the mapper interface
          // look at MapperAnnotationBuilder#loadXmlResource
          configuration.addLoadedResource("namespace:" + namespace);

            //所以,如果在XML中配置的namespace是一个类的话,
            //那么 mybatis在解析时,肯定就会将这个类找出来,并对上面的注解加以解析.

            //那么现在问题来了, 如果某个方法即在xml中定义了,又有@Select注解,应该如何破呢?
            //经过排查, 由于在添加statement时,并没有判断configuration中是否存在,
            // 但是, configuration使用的是StrictMap, 不允许重复添加

            // 所以,上面说这种情况是不允许存在的.
          configuration.addMapper(boundType);
        }
      }
    }
  }

}
