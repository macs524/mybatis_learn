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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

import java.util.List;
import java.util.Locale;

/**
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {

    private final MapperBuilderAssistant builderAssistant;
    private final XNode context;
    private final String requiredDatabaseId;

    public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
        this(configuration, builderAssistant, context, null);
    }

    /**
     * 构造函数
     * @param configuration 配置
     * @param builderAssistant Mapper辅助类,有一些公共通用的方法
     * @param context 语句节点
     * @param databaseId 数据库ID,通常这个是NULL
     */
    public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context, String databaseId) {
        super(configuration);
        this.builderAssistant = builderAssistant;
        this.context = context;
        this.requiredDatabaseId = databaseId;
    }

    /**
     * 终极解析. 这个解析出来,就是一条条的语句了.
     *
     * 加把劲,争取今天分析完
     */
    public void parseStatementNode() {
        String id = context.getStringAttribute("id"); //id
        String databaseId = context.getStringAttribute("databaseId");

        if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
            return;
        }


        //解析各个参数
        Integer fetchSize = context.getIntAttribute("fetchSize");//获取记录的总条数设定
        Integer timeout = context.getIntAttribute("timeout"); //超时时间, 单位为秒
        String parameterMap = context.getStringAttribute("parameterMap");
        // 参数Map的名字,即前面分析的参数,现在已经较少使用了

        //参数类型
        String parameterType = context.getStringAttribute("parameterType");
        Class<?> parameterTypeClass = resolveClass(parameterType);

        //这两个二选一
        String resultMap = context.getStringAttribute("resultMap");
        String resultType = context.getStringAttribute("resultType");
        Class<?> resultTypeClass = resolveClass(resultType);

        //语言驱动器
        String lang = context.getStringAttribute("lang");
        LanguageDriver langDriver = getLanguageDriver(lang);

        //resultSetMap,通常我们也不用设置
        String resultSetType = context.getStringAttribute("resultSetType");
        ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);

        //语句类型,默认应该是prepared
        StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString()));

        //根据节点名设置SQLCommand, 目前只支持select, update, insert, delete
        String nodeName = context.getNode().getNodeName();
        SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));


        boolean isSelect = sqlCommandType == SqlCommandType.SELECT; //是否为select语句
        boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);//对于select来说,默认是false,但其它默认就是true了
        boolean useCache = context.getBooleanAttribute("useCache", isSelect);
        boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false); //默认为false

        // Include Fragments before parsing
        //处理include子句,进行sql子句替换和变量替换
        XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
        includeParser.applyIncludes(context.getNode());

        // Parse selectKey after includes and remove them.
        //处理selectKey
        processSelectKeyNodes(id, parameterTypeClass, langDriver);

        // Parse the SQL (pre: <selectKey> and <include> were parsed and removed)
        //解决了两大难题

        //langDriver一定不会为空的. 而且负责创建SqlSource,所以这是很重要的.
        //这一步很复杂, 不过执行完之后 ,我们会得到一个最终可以使用的SQL,只是参数名变成了?
        SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);

        //三个属性设置
        String resultSets = context.getStringAttribute("resultSets");

        // 这两个属性只能在insert或update语句上设置,表示主键列
        String keyProperty = context.getStringAttribute("keyProperty");
        String keyColumn = context.getStringAttribute("keyColumn");

        KeyGenerator keyGenerator;
        String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;

        // 这儿的调用参数使用到了true , 感觉是有点问题,应该用false
        // 但鉴于上下文代码, keyStatementId不可能有 . , 所以true和false的意义不大
        // 处理完之后, keyStatementId的值应该都会带上当前的命名空间
        keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);


        // 实际上就是看有没有selectKey, 有的话优先以selectKey定义的为主
        if (configuration.hasKeyGenerator(keyStatementId)) {
            //如果有selectKey,则以selectKey为准
            keyGenerator = configuration.getKeyGenerator(keyStatementId);
        } else {
            //否则,根据是否设置了useGeneratedKeys来决定使用哪一种主键策略
            keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
                    configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
                    ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
        }


        //终极处理,添加MappedStatement.
        builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
                fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
                resultSetTypeEnum, flushCache, useCache, resultOrdered,
                keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);

        //到这里就算是处理完了.
    }

    /**
     * 处理select Key
     * @param id 语句ID
     * @param parameterTypeClass 参数类型,可能为NULL
     * @param langDriver 语言驱动器
     */
    private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
        List<XNode> selectKeyNodes = context.evalNodes("selectKey");//所有的selectKey.
        if (configuration.getDatabaseId() != null) {
            parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
        }
        parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
        removeSelectKeyNodes(selectKeyNodes);
    }

    /**
     * 解析selectKey节点
     * @param parentId selectKEY语句的父ID, 也就是语句的ID
     * @param list selectKeyNode节点, 也就是说一个语句下可以定义多个select Key.
     * @param parameterTypeClass 参数类型
     * @param langDriver 语言驱动器
     * @param skRequiredDatabaseId 系统配置的databaseId, 一般情况下为NULL
     */
    private void parseSelectKeyNodes(String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
        for (XNode nodeToHandle : list) {

            //parentId是一样的,前缀也一样,那么每个ID不也就一样了吗? 但是通过判断保证了不会解析多个, 而只会能一条
            String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
            String databaseId = nodeToHandle.getStringAttribute("databaseId");
            if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
                parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
            }
        }
    }

    /**
     * 处理单个selectKey
     *
     * 示例如下:
     * <selectKey keyProperty="id" resultType="int" order="BEFORE">
     select CAST(RANDOM()*1000000 as INTEGER) a from SYSIBM.SYSDUMMY1
     </selectKey>
     * @param id id
     * @param nodeToHandle 待处理的节点
     * @param parameterTypeClass 节点的参数类型
     * @param langDriver 语言驱动器,很可能为默认
     * @param databaseId databaseId
     */
    private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {

        //1. 返回值类型
        String resultType = nodeToHandle.getStringAttribute("resultType");
        Class<?> resultTypeClass = resolveClass(resultType);
        //2. 语句类型,默念为prepared
        StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType",
                StatementType.PREPARED.toString()));
        //3. 主键属性
        String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
        //4. 主键列属性
        String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
        //5. 是添加前执行还是添加后执行
        boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

        //defaults, 由于Statement的参数众多,而selectKey是一个特殊的Statement,所以可以为很多属性设置默认值.
        boolean useCache = false;
        boolean resultOrdered = false;
        KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
        Integer fetchSize = null;
        Integer timeout = null;
        boolean flushCache = false;
        String parameterMap = null;
        String resultMap = null;
        ResultSetType resultSetTypeEnum = null;

        //看做一个选择子句
        SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
        SqlCommandType sqlCommandType = SqlCommandType.SELECT;

        //最终,将其作为一个select子句添加到configuration中去.
        builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
                fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
                resultSetTypeEnum, flushCache, useCache, resultOrdered,
                keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);

        id = builderAssistant.applyCurrentNamespace(id, false);

        MappedStatement keyStatement = configuration.getMappedStatement(id, false);
        //同时添加一个主键生成器.
        configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
    }

    /**
     * 移除所有的selectKey节点
     * @param selectKeyNodes
     */
    private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
        for (XNode nodeToHandle : selectKeyNodes) {
            nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
        }
    }

    /**
     * 比较databaseId是否一致,我们来看一下具体的逻辑
     * @param id 语句ID,
     * @param databaseId 语句节点配置的 databseId, 可能为NULL
     * @param requiredDatabaseId 系统配置的 databaseId, 可能为NULL
     * @return 结果
     */
    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {

        // 分两种情况
        if (requiredDatabaseId != null) {
            // 1. 如果系统配置了databseId, 比如其值为mysql, 那么selectKey的databaseId也需要是mysql, 否则匹配失败
            if (!requiredDatabaseId.equals(databaseId)) {
                return false;
            }
        } else {

            // 2. 如果系统没有配置,那么selectKey也不能配置, 也就是说都需要是NULL
            if (databaseId != null) {
                return false;
            }
            // skip this statement if there is a previous one with a not null databaseId
            id = builderAssistant.applyCurrentNamespace(id, false); // 给ID补上命名空间,如果有的话

            /**
             * 这里判断系统里有没有那个语句, 如果有的话返回
             * 这里第二一个参数传false, 主要是在不存在该语句时,不重新解析那些未解析成功的语句
             * 那么问题来了,为什么不呢. 没有必要, 因为这条语句正在处理呢, 如果因为某些原因导致解析失败,那么重新解析也一样会失败.
             */
            if (this.configuration.hasStatement(id, false)) {
                MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
                // 如果系统已经定义了一条带databseId的,那么就不处理了,什么意思呢?
                // 按理说正常情况下不会是这样的. 最重要的是,什么样的情况下,会有这样的情况发生?难道是注解?
                if (previous.getDatabaseId() != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 获取语言驱动
     *
     * Configuration 在初始化的时候, 添加了两个别名,分别如下:
     *
     * typeAliasRegistry.registerAlias("XML", XMLLanguageDriver.class);
     typeAliasRegistry.registerAlias("RAW", RawLanguageDriver.class);

     所以如果不想用自定义的话,可以设置为xml或raw, 而且默认的Driver是XMLLanguageDriver.
     所以只有需要使用RawLanguageDriver的时候才需要指定.

     raw的意思是原始的,未加工的,这里的意思是表示静态的SQL, 也就是说, 用RawLanguageDriver产生的
     都是静态的SQL, 由于使用mysql主要作用是使用其动态功能,而XMLLanguageDriver也能够兼容静态了,
     所以XMLLanguageDriver已经没有太多使用的价值,可以忽略.

     * @param lang languageDriver的别名或者全称
     * @return
     */
    private LanguageDriver getLanguageDriver(String lang) {
        Class<?> langClass = null;
        if (lang != null) {
            langClass = resolveClass(lang);
        }
        return builderAssistant.getLanguageDriver(langClass);
    }

}
