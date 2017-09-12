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
package org.apache.ibatis.builder.xml;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

/**
 * 这个构造器是解析mybatis配置文件的关键类
 *
 *
 * 这个函数在初始化时， 完成了 配置文件 由 InputStream 到 Document 的转化，
 * 借助 XPathParser 来完成。 XPathParser 封装了JAVA 采用 DOM 解析 XML的过程， 并持有了 解析后的 Document 对象
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

    private boolean parsed;
    private final XPathParser parser;
    private String environment;
    private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

    public XMLConfigBuilder(Reader reader) {
        this(reader, null, null);
    }

    public XMLConfigBuilder(Reader reader, String environment) {
        this(reader, environment, null);
    }

    public XMLConfigBuilder(Reader reader, String environment, Properties props) {
        this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    /**
     * 根据输入流对象构造， 这个输入流代表了对应的 mybatis-config.xml 文件
     * @param inputStream 输入流
     */
    public XMLConfigBuilder(InputStream inputStream) {
        this(inputStream, null, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment) {
        this(inputStream, environment, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
        this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    /**
     * 构造配置文件解析器，当这个构造函数调用时，
     * parser对象已经完成了对于目标文件的解析，并已经生成了Document对象
     *
     * 也就是说，构造完成后，以下事情已经准备就绪
     *
     * 1）目标XML已经解析为Document
     * 2）Configuration 已经初始化
     * 3）初始化时，默认没有解析， 这个解析指的是 从 Document 中 分析出一些重要属性，存储在 Configuration 里
     *
     * Document 对象是parser的一个属性
     *
     * @param parser parser
     * @param environment 环境参数，可以为NULL
     * @param props 配置文件
     */
    private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
        super(new Configuration());
        ErrorContext.instance().resource("SQL Mapper Configuration");
        this.configuration.setVariables(props);
        this.parsed = false;
        this.environment = environment;
        this.parser = parser;
    }

    /**
     * 进行解析操作，由此进入复杂的解析过程， 这个方法一般是在构造SessionFactory时调用
     *
     * 由于这个方法是解析配置文件的入口，所以相当重要。实际上只是对解析操作的简单封装，避免重复处理。
     *
     * @return 解析之后，返回负载满满的配置对象
     */
    public Configuration parse() {
        //保证只解析一次
        if (parsed) {
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        parsed = true;
        //开始解析, 一个配置文件总是从configuration中出手的
        parseConfiguration(parser.evalNode("/configuration"));
        return configuration;
    }

    /**
     * 从指定节点开始解析， 其实也只有一次调用，这个指定节点就是根节点
     * 但是方法拆分后还是要清晰一些，值得学习
     *
     * 这个基本上可以称之为是解析过程的核心。 是配置从文件到Configuration的关键所在。
     *
     * 核心13招
     * @param root 根节点， 也就是configuration 节点， 这里需要注意的是参数是XNode类型，这个是对原生Node类型的一种封装，取属性更好用。
     */
    private void parseConfiguration(XNode root) {
        try {
            //issue #117 read properties first

            //1. 解析属性节点
            propertiesElement(root.evalNode("properties"));
            //2. 解析配置节点
            Properties settings = settingsAsProperties(root.evalNode("settings"));
            //3. 设置VFS实现类，如果指定了的话
            loadCustomVfs(settings);
            //4. 解析别名节点
            typeAliasesElement(root.evalNode("typeAliases"));
            //5. 解析插件节点
            pluginElement(root.evalNode("plugins"));
            //6. 解析objectFactory节点
            objectFactoryElement(root.evalNode("objectFactory"));
            //7. 解析objectWrapperFactory 节点
            objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
            //8. 解析reflectoryFactory节点
            reflectorFactoryElement(root.evalNode("reflectorFactory"));
            //9. 将属性设置到configuration中去，依赖于第二步解析出来的settings属性
            settingsElement(settings);
            // read it after objectFactory and objectWrapperFactory issue #631
            //10. 解析环境变量
            environmentsElement(root.evalNode("environments"));
            //11. 解析databaseIdProvider
            databaseIdProviderElement(root.evalNode("databaseIdProvider"));
            //12. 解析typeHandler
            typeHandlerElement(root.evalNode("typeHandlers"));
            //13. 解析Mapper
            mapperElement(root.evalNode("mappers"));

            //至此,对于 Mybatis-Config.xml的配置文件解析就完成了, 解析之后的结果,也都存储在了Configurtaion
            //但是,对于解析的分析并没有结束, 对于Mapper的解析,相对来说比这个要复杂很多,那个才是重中之中
            //我们的目标,就是要在今天完成对于 Mapper 解析的整个过程分析.

            // 这个过程完全熟悉了,对于后续调用的分析,就会简单很多.
        } catch (Exception e) {
            throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
        }
    }

    /**
     * 解析配置信息，这个配置信息实际上也就是Configuration中的各个参数，可以不设置，因为有默认值
     * 但必须是Configuration中有对应属性或有对应setter方法的
     * @param context 目标节点
     * @return 结果
     */
    private Properties settingsAsProperties(XNode context) {
        if (context == null) {
            return new Properties();
        }

        //先获取其下的所有参数
        Properties props = context.getChildrenAsProperties();
        // Check that all settings are known to the configuration class

        // 这个必须是使用默认的reflectorFactory， 而不是使用自定义的，因为自定义的质量不能保证
        // 使用这个类方便了对于属性及方法的判断，基于反射实现
        MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);

        //说明这里不是随便设置的，每一个必须在Configuration中有对应的设置方法
        for (Object key : props.keySet()) {
            if (!metaConfig.hasSetter(String.valueOf(key))) {
                throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
            }
        }
        return props;
    }

    /**
     * 这个在settings中定义，具体是定义 vfsImpl 属性， 这个需要是类的全路径
     * @param props 属性
     * @throws ClassNotFoundException
     */
    private void loadCustomVfs(Properties props) throws ClassNotFoundException {
        String value = props.getProperty("vfsImpl");
        if (value != null) {
            String[] clazzes = value.split(",");
            for (String clazz : clazzes) {
                if (!clazz.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources.classForName(clazz);
                    configuration.setVfsImpl(vfsImpl);
                }
            }
        }
    }

    /**
     * 解析别名配置
     * 别名的存储其实很简单,就是在Configuration内部有一个MAP,存储了别名和其对应的class类的对应关系.
     * 别名配置有两种方式
     * 1)通过package指定
     *    <package name='com.xxx.xxx' />
     * 2)直接指定
     *    <typeAlias alias='role' type='com.xxxx.XXXRole' />
     *
     *  这两个可以反复使用
     *
     *  别名可能是以下一些值
     *  1）直接通过alias指定
     *  2）使用@Alias 指定
     *  3）类的简单名称， 比如 com.xxx.XXXRole, 其SimpleName 一定是 XXXRole.
     *
     *  另外系统是通过一个Map 来存储的，别名即为Map的KEY，而且在存储前将别名转化成了小写，所以别名是大小写不敏感的。
     *
     *  通常情况下，我们保持使用类的简单名称就好，不用特意指定alias 和 @Alias.
     *
     *
     * @param parent 待处理的节点, 在配置文件中指 typeAliases 节点
     */
    private void typeAliasesElement(XNode parent) {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                //遍历其所有子节点,所以别名可以定义多个,也可以混合使用
                if ("package".equals(child.getName())) {
                    String typeAliasPackage = child.getStringAttribute("name");
                    typeAliasRegistry.registerAliases(typeAliasPackage);
                } else {
                    //这种方式比较简单,可以不指定别名,但类型一定要指定
                    String alias = child.getStringAttribute("alias");
                    String type = child.getStringAttribute("type");
                    try {
                        Class<?> clazz = Resources.classForName(type); //这里是直接解析,所以不存在别名的别名
                        if (alias == null) {
                            typeAliasRegistry.registerAlias(clazz);
                        } else {
                            typeAliasRegistry.registerAlias(alias, clazz); //指定别名
                        }
                    } catch (ClassNotFoundException e) {
                        throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                    }
                }
            }
        }
    }

    /**
     * 解析plugins 节点,一个 plugins节点定义如下:
     *
     *  <plugins>
            <plugin interceptor="org.apache.ibatis.builder.ExamplePlugin">
                <property name="pluginProperty" value="100"/>
            </plugin>
        </plugins>

     可以看到,其子节点最重要的属性就是interceptor, 指向自定义或者是已有一的些拦截器类名,还可以自定义属性.

     这个作用是将其加入到拦截器链中去， 在interceptor定义拦截器类，必须实现Interceptor接口
     * @param parent plugins节点
     * @throws Exception
     */
    private void pluginElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                String interceptor = child.getStringAttribute("interceptor"); // 这个可以 是别名
                Properties properties = child.getChildrenAsProperties(); //获取这个节点的所有属性,这个方法我们之前分析过.
                Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
                interceptorInstance.setProperties(properties);

                //将解析出来的结果添加到拦截器列表里
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    /**
     * 解析objectFactory, 该节点的定义如下:
     *  <objectFactory type="org.apache.ibatis.builder.ExampleObjectFactory">
            <property name="objectFactoryProperty" value="100"/>
        </objectFactory>

     从节点的定义上看, 解析需要从type属性中获取其对应的类名,以及存储所有的属性值（可以指定属性）
     而方法也正是这样做的.

     * @param context objectFactory节点
     * @throws Exception
     */
    private void objectFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type"); //获取类名
            Properties properties = context.getChildrenAsProperties(); //获取属性
            //创建一个对象,并设置属性,这里使用了resolveClass,所以是可以使用别名的
            ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance(); //
            factory.setProperties(properties);

            //最终还是要放到大的配置对象中去
            configuration.setObjectFactory(factory);
        }
    }

    /**
     * 解析objectWrapperFactory节点,这个节点的定义比较简单,如下:
     *
     * <objectWrapperFactory type="org.apache.ibatis.builder.CustomObjectWrapperFactory" />
     *
     * 这个没有属性
     *
     * @param context 待处理的节点
     * @throws Exception
     */
    private void objectWrapperFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type"); //从type中获取到类名并反射创建即可
            ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
            configuration.setObjectWrapperFactory(factory);
        }
    }

    /**
     * 解析 reflectorFactory 节点,也是相当简单,节点定义如下:
     *
     * <reflectorFactory type="org.apache.ibatis.builder.CustomReflectorFactory"/>
     *
     * @param context 待解析的节点
     * @throws Exception
     */
    private void reflectorFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
            configuration.setReflectorFactory(factory);
        }
    }

    /**
     * 解析属性节点，对于Mybatis来说，属性有三种配置方式
     *
     * 1）XML中属性节点中直接配置，如：
     *    <properties>
     *        <property name='aaa'>bbb</property>
     *        <property name='a2'>b2</property>
     *    </properties>
     * 2) 配置resource或者url节点，指代一个配置文件的路径
     * 3）直接在代码中进行提供
     *
     * 那么很明显，这里只解析那些在XML中配置的，也就是1，和2
     *
     * 同名的属性是可以替换的，原则上是后解析的替换前解析的，所以这和解析顺序有关。
     * @param context
     * @throws Exception
     */
    private void propertiesElement(XNode context) throws Exception {
        if (context != null) {

            //1. 解析子属性节点。
            Properties defaults = context.getChildrenAsProperties();

            //2. 解析属性文件中的属性，那么很明显，2会替换1的同名变量
            String resource = context.getStringAttribute("resource");
            String url = context.getStringAttribute("url");
            if (resource != null && url != null) {
                // 不能同时都配置
                throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
            }
            if (resource != null) {
                defaults.putAll(Resources.getResourceAsProperties(resource));
            } else if (url != null) {
                defaults.putAll(Resources.getUrlAsProperties(url));
            }

            //3. 之前代码中添加的，将其取出来放到defaults里，所以，在代码中添加的优先级最高。
            Properties vars = configuration.getVariables();
            if (vars != null) {
                defaults.putAll(vars);
            }

            //重设
            parser.setVariables(defaults);  // 更新变量
            configuration.setVariables(defaults);
        }
    }

    /**
     * 将settings中设置的各项值设置到configuration对象中去
     * 如果没有,则设置默认值
     *
     * 所以默认值的设置是在解析过程中进行的,而不是在创建对象时.
     *
     * 注意在解析过程中用到了别名,所以这个设置过程一定要在别名解析之后.
     *
     *
     * 这里面的属性，有一些比较简单，就是设置字面值，比如int, string, 之类的
     * 有一些是需要解析为对应的类，那么这些属性值就是类的全限定名
     *
     * @param props 配置项
     * @throws Exception
     */
    private void settingsElement(Properties props) throws Exception {

        //1. 自动映射行为，枚举PARTIAL
        configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
        //2. 对于自动映射时遇到无法识别的列的处理，默认不处理
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
        //3. 是否开启缓存
        configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
        //4. 代理工厂，默认使用JavaAssistantProxyFactory
        configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
        //5. 是否支持延迟加载
        configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
        configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
        configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
        configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
        //是否使用生成的ID
        configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
        //默认执行器，枚举
        configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));

        //这两个没有默认值
        configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
        configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));

        configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
        configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));

        //缓存范围
        configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
        configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));


        configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
        configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));

        //默认脚本语言， XMLLanguageDriver
        configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));

        //默认枚举类型处理器，默认是 EnumTypeHandler.class
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler> typeHandler = (Class<? extends TypeHandler>) resolveClass(props.getProperty("defaultEnumTypeHandler"));
        configuration.setDefaultEnumTypeHandler(typeHandler);

        configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
        configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
        configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
        configuration.setLogPrefix(props.getProperty("logPrefix"));

        @SuppressWarnings("unchecked")
        Class<? extends Log> logImpl = (Class<? extends Log>) resolveClass(props.getProperty("logImpl"));
        configuration.setLogImpl(logImpl);

        //这个好像是没有默认值
        configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    }

    /**
     * 解析环境节点,相比前面几个,这个要稍微复杂一些,一个常见的节点定义如下:
     *
     *  <environments default="development">  ## 默认使用的配置
             <environment id="development"> ## 每一个定义的环境

                <transactionManager type="JDBC" > ## 定义两个对象，一是事务管理器
                    <property name="" value="" />
                </transactionManager>

                <dataSource type="UNPOOLED"> ## 数据源
                     <property name="driver" value="${driver}" />
                     <property name="url" value="${url}" />
                     <property name="username" value="${username}"/>
                     <property name="password" value="${password}"/>
                </dataSource>

             </environment>
        </environments>

     从上面的节点来看,首先是可以定义多个,其次属性中可能出来了引用变量.
     *
     *
     *
     *
     *
     * @param context environments 节点
     * @throws Exception
     */
    private void environmentsElement(XNode context) throws Exception {
        if (context != null) {
            if (environment == null) {
                // 这个只有在代码中定义才可能不为NULL，通常来说应该是NULL的
                environment = context.getStringAttribute("default"); //所以default节点就不能改变了
            }

            for (XNode child : context.getChildren()) {
                //解析各个environment节点
                String id = child.getStringAttribute("id"); //取ID
                if (isSpecifiedEnvironment(id)) {
                    //只解析与之匹配的节点
                    TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
                    DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));

                    DataSource dataSource = dsFactory.getDataSource();

                    //Environment 封装了 TransactionFactory 和 DataSource.
                    Environment.Builder environmentBuilder = new Environment.Builder(id) // 这个必须提供
                            .transactionFactory(txFactory) //必须提供
                            .dataSource(dataSource); //必须提供

                    configuration.setEnvironment(environmentBuilder.build()); // 设置环境对象，这样就可以从中获取到数据源和事务管理器了
                }
            }
        }
    }

    /**
     * 这个节点相对来说还是比较简单的,如下:
     *
     * <databaseIdProvider type="DB_VENDOR">
            <property name="Apache Derby" value="derby"/>
        </databaseIdProvider>

     和前面一样, 解析的重点是获取type和属性.

     每一项配置项的解析,最终都是为了设置configuration中的某一项或者某几项值

     这个节点解析主要是为了设置databaseId. 这是在设置了environment的前提下.

     Configuration 类默认注册了
     typeAliasRegistry.registerAlias("DB_VENDOR", VendorDatabaseIdProvider.class);
     所以默认可以引用这个。

     * @param context databaseIdProvider 节点
     * @throws Exception
     */
    private void databaseIdProviderElement(XNode context) throws Exception {
        DatabaseIdProvider databaseIdProvider = null;
        if (context != null) {
            String type = context.getStringAttribute("type");
            // awful patch to keep backward compatibility
            if ("VENDOR".equals(type)) {
                type = "DB_VENDOR";
            }
            Properties properties = context.getChildrenAsProperties();
            databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
            databaseIdProvider.setProperties(properties);
        }


        Environment environment = configuration.getEnvironment();
        if (environment != null && databaseIdProvider != null) {
            String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());

            configuration.setDatabaseId(databaseId);
        }
    }

    /**
     * 解析事务管理器,节点定义如下:
     * <transactionManager type="JDBC">
            <property name="" value=""/>
        </transactionManager>
     可见重要的是type属性和里面的一些配置,这里type很明显使用到了别名.而且是内部自定义的.

     Configuration 在初始化时注册了以下两个类

     typeAliasRegistry.registerAlias("JDBC", JdbcTransactionFactory.class);
     typeAliasRegistry.registerAlias("MANAGED", ManagedTransactionFactory.class);

     所以type的选择实际上也只有两种，即JDBC 和 MANAGED, 当然，也可以自己定义，然后在alias节点进行添加

     从JdbcTransactionFactory的实现来看，并没有使用到Properties, 所以如果使用JDBC，就没有设置属性的必要了。

     * @param context transactionManager 节点
     * @return
     * @throws Exception
     */
    private TransactionFactory transactionManagerElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");  //主要还是根据类型
            Properties props = context.getChildrenAsProperties();
            TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a TransactionFactory.");
    }

    /**
     * 解析dataSource节点,节点示范定义如下:
     *
     *  <dataSource type="UNPOOLED">
            <property name="driver" value="${driver}"/>
            <property name="url" value="${url}"/>
            <property name="username" value="${username}"/>
            <property name="password" value="${password}"/>
        </dataSource>

     同样,我们也是只需要关注type属性和子属性节点值. 注意这里虽然出现了${}, 但只要配置文件中有
     这个是会被替换的.

        Configuration中默认注册了以下几个数据源

        typeAliasRegistry.registerAlias("JNDI", JndiDataSourceFactory.class);
        typeAliasRegistry.registerAlias("POOLED", PooledDataSourceFactory.class);
        typeAliasRegistry.registerAlias("UNPOOLED", UnpooledDataSourceFactory.class);

     DataSourceFactory 接口有一个关键的getDataSource节口，用于提供数据源
     * @param context dataSource
     * @return 不是具体的数据源，而是数据源工厂
     * @throws Exception
     */
    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            Properties props = context.getChildrenAsProperties();
            DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    /**
     * 解析typeHandler, 这个相对来说要复杂点,节点定义如下:
     *
     *   <typeHandlers>
     <typeHandler javaType="String" handler="org.apache.ibatis.builder.CustomStringTypeHandler"/>
     <typeHandler
     javaType="String"
     jdbcType="VARCHAR"
     handler="org.apache.ibatis.builder.CustomStringTypeHandler"
     />
     <typeHandler handler="org.apache.ibatis.builder.CustomLongTypeHandler"/>
     <package name="org.apache.ibatis.builder.typehandler"/>
     </typeHandlers>

     两种方式, 通过package扫描,或者是直接配置

     在直接配置中,如果明确指定了javaType和jdbcType,则直接建议它们三者的映射关系.
     如果没有指定javaType, 则还可以从MappedTypes中查找所注解的类
     如果没有指定jdbcType, 也可以从MappedJdbcTypes中查找所注意的JDBC类型.

     所有的注册, 最终在底层表现为两个MAP

     1) ALL_TYPE_HANDLERS_MAP: 这个MAP中存储了handler类及实例的映射关系,由于注册时handler必须存在,所以无论是哪个版本的注册方法,
     都会在这里存储handler
     2) TYPE_HANDLER_MAP: 这是一个嵌套MAP,其中KEY为Java类型, 内层的MAP,KEY是JDBC类型,值为对应的Handler.
     也就是说,这三者的映射关系,是以java类型为KEY作为维度来区分的.

     经过这样分析之后, typeHandler在mybatis中的存储就算是清楚了,后续就是看如何使用.
     * @param parent
     * @throws Exception
     */
    private void typeHandlerElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                if ("package".equals(child.getName())) {
                    //第一种方式,直接解析整个指定的包
                    String typeHandlerPackage = child.getStringAttribute("name");
                    typeHandlerRegistry.register(typeHandlerPackage);
                } else {
                    //第二种方式,解析指定的单个typeHandler
                    String javaTypeName = child.getStringAttribute("javaType");
                    String jdbcTypeName = child.getStringAttribute("jdbcType");


                    Class<?> javaTypeClass = resolveClass(javaTypeName);// 如果没有直接设置javaType, 则这个可能是NULL
                    JdbcType jdbcType = resolveJdbcType(jdbcTypeName); // 如果没有直接设置jdbcType, 则这个也有可能是NULL

                    //类型定义一定要存在,可以是别名
                    String handlerTypeName = child.getStringAttribute("handler");
                    Class<?> typeHandlerClass = resolveClass(handlerTypeName);

                    if (javaTypeClass != null) {
                        //在设置了javaType之后,会走这个分支
                        if (jdbcType == null) {
                            typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
                        } else {
                            //如果JDBC也设置了,那么直接调用最终的注册方法
                            typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
                        }
                    } else {
                        //如果javaTypeClass为NULL,则不考虑jdbcType的配置,直接只使用typeHandlerClass
                        typeHandlerRegistry.register(typeHandlerClass);
                    }
                }
            }
        }
    }

    /**
     * 对于mapper的解析, 由于mapper 中的属性是XML文件路径, 而mapper文件的定义相当复杂,所以
     * 这个节点的解析也会跟着变得比较复杂.
     *
     * 定义如下:
     <mappers>
     <mapper resource="org/apache/ibatis/builder/BlogMapper.xml"/>
     <mapper url="file:./src/test/java/org/apache/ibatis/builder/NestedBlogMapper.xml"/>
     <mapper class="org.apache.ibatis.builder.CachedAuthorMapper"/>
     <package name="org.apache.ibatis.builder.mapper"/>
     </mappers>

     可见对于mappers节点的解析来说, 总的来说有两种方式,一种是使用<mapper /> 节点,另一种是使用<package />

     如果使用mapper, 根据常识以及实际的解析逻辑,我们只能指定这三个属性中的其中一个,而且必须指定一个.

     通过对每个分支的代码大致分析,可以得到如下结论:

     这里的加载和解析主要是两种方式

     1)直接添加类, 对于package方式和直接指定class的方式来说, 就是将这个Mapper类直接添加到configuration里, 所以使用package,
     并不是处理该包下的Mapper配置文件,而是直接找的Mapper类,这个必须要非常确认.
     而且对于其配置的解析只适合于放在该类上的相关注解.
     2) 读取文件,这个就是实打实的分析配置文件,相对也更为复杂.

     解析完成后,  最终也是会放到Configuration的一个Map里,还可以避免重复添加.
     * @param parent
     * @throws Exception
     */
    private void mapperElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                if ("package".equals(child.getName())) {
                    String mapperPackage = child.getStringAttribute("name");
                    configuration.addMappers(mapperPackage);
                } else {
                    String resource = child.getStringAttribute("resource");
                    String url = child.getStringAttribute("url");
                    String mapperClass = child.getStringAttribute("class");
                    if (resource != null && url == null && mapperClass == null) {
                        ErrorContext.instance().resource(resource);

                        //1. 读取资源
                        InputStream inputStream = Resources.getResourceAsStream(resource);
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());

                        //2. 解析
                        mapperParser.parse();
                    } else if (resource == null && url != null && mapperClass == null) {

                        //从URL中读取资源
                        ErrorContext.instance().resource(url);
                        InputStream inputStream = Resources.getUrlAsStream(url);

                        //解析.
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
                        mapperParser.parse();
                    } else if (resource == null && url == null && mapperClass != null) {
                        Class<?> mapperInterface = Resources.classForName(mapperClass);

                        //下面我们来分析,如果mapper直接指的是一个Class呢?
                        configuration.addMapper(mapperInterface);
                    } else {
                        throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
                    }
                }
            }
        }
    }

    /**
     * 判断是否为指定的环境
     * @param id 具体元素的环境ID
     * @return
     */
    private boolean isSpecifiedEnvironment(String id) {
        if (environment == null) {
            throw new BuilderException("No environment specified.");
        } else if (id == null) {
            throw new BuilderException("Environment requires an id attribute.");
        } else if (environment.equals(id)) {
            return true;
        }
        return false;
    }

}
