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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * XML节点解析器, 主要是解析节点里的SQL
 * @author Clinton Begin
 */
public class XMLScriptBuilder extends BaseBuilder {

    private final XNode context;
    private boolean isDynamic;
    private final Class<?> parameterType;

    public XMLScriptBuilder(Configuration configuration, XNode context) {
        this(configuration, context, null);
    }

    /**
     * 构造函数
     * @param configuration 配置
     * @param context 节点
     * @param parameterType 参数类型, 这个可能为NULL
     */
    public XMLScriptBuilder(Configuration configuration, XNode context, Class<?> parameterType) {
        super(configuration);
        this.context = context;
        this.parameterType = parameterType;
    }

    /**
     * 将SQL节点解析为SQL Source.
     *
     * SQL节点就是我们说的insert,update,delete, select
     * 示例如下:
     *
     * <select id="selectXml" resultType="Name" lang="xml">
     SELECT firstName
     <if test="includeLastName != null">, lastName</if>
     FROM names
     WHERE lastName LIKE #{name}
     </select>
     * @return
     */
    public SqlSource parseScriptNode() {

        //1. 把XNode转化为SqlNode列表
        List<SqlNode> contents = parseDynamicTags(context);
        //2. 用MixedSqlNode将 sqlNode 列表组合起来
        MixedSqlNode rootSqlNode = new MixedSqlNode(contents); // 明显的装饰器
        SqlSource sqlSource = null;
        if (isDynamic) {
            //是动态语句
            sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
        } else {
            //是静态语句
            sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
        }
        return sqlSource;
    }

    /**
     * 把XNode的子节点转化为SqlNode节点
     * @param node 语句节点
     * @return SQL列表
     */
    List<SqlNode> parseDynamicTags(XNode node) {
        List<SqlNode> contents = new ArrayList<SqlNode>();

        //原生子节点, node不是有一个方法叫做getChildren吗? 但在这里不能用,因为那个方法只查找
        // 节点类型是Element的, 而这里我们还要处理文节点
        NodeList children = node.getNode().getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            XNode child = node.newXNode(children.item(i));

            if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE
                    || child.getNode().getNodeType() == Node.TEXT_NODE) {

                //是文本节点, 比如本示例中的 SELECT firstName, 就是第一个节点,也是文本节点
                //其body内容也肯定就是 SELECT firstName.

                String data = child.getStringBody("");
                TextSqlNode textSqlNode = new TextSqlNode(data);
                if (textSqlNode.isDynamic()) {
                    //动态节点,在apply的时候还要再解析.
                    contents.add(textSqlNode);
                    isDynamic = true;
                } else {
                    //静态节点, 不需要任何解析
                    contents.add(new StaticTextSqlNode(data));
                }
            } else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628

                //不是文本节点,可能是什么节点呢,这个比较好理解,那肯定就是
                //if, choose, trim, foreach之类的节点了.
                String nodeName = child.getNode().getNodeName(); //所以我们要获取节点名
                NodeHandler handler = nodeHandlers(nodeName); //并找出一个对应的节点处理器
                if (handler == null) {
                    throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
                }

                //对动态节点进行处理, 处理之后, 将是一个个的SQLNode对象了.
                handler.handleNode(child, contents);
                isDynamic = true;
            }
        }
        return contents;
    }

    /**
     * 这儿就很明确的展示了可能有哪些节点及对应的处理器
     * @param nodeName
     * @return
     */
    NodeHandler nodeHandlers(String nodeName) {
        Map<String, NodeHandler> map = new HashMap<String, NodeHandler>();
        map.put("trim", new TrimHandler());
        map.put("where", new WhereHandler());
        map.put("set", new SetHandler());
        map.put("foreach", new ForEachHandler());
        map.put("if", new IfHandler());
        map.put("choose", new ChooseHandler());
        map.put("when", new IfHandler());
        map.put("otherwise", new OtherwiseHandler());
        map.put("bind", new BindHandler());
        return map.get(nodeName);
    }

    private interface NodeHandler {
        void handleNode(XNode nodeToHandle, List<SqlNode> targetContents);
    }

    private class BindHandler implements NodeHandler {
        public BindHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            final String name = nodeToHandle.getStringAttribute("name");
            final String expression = nodeToHandle.getStringAttribute("value");
            final VarDeclSqlNode node = new VarDeclSqlNode(name, expression);
            targetContents.add(node);
        }
    }

    private class TrimHandler implements NodeHandler {
        public TrimHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            //这两条语句基本上是固定的
            List<SqlNode> contents = parseDynamicTags(nodeToHandle);
            MixedSqlNode mixedSqlNode = new MixedSqlNode(contents);

            //对于trim来说,这四个参数很重要
            String prefix = nodeToHandle.getStringAttribute("prefix");
            String prefixOverrides = nodeToHandle.getStringAttribute("prefixOverrides");
            String suffix = nodeToHandle.getStringAttribute("suffix");
            String suffixOverrides = nodeToHandle.getStringAttribute("suffixOverrides");

            TrimSqlNode trim = new TrimSqlNode(configuration, mixedSqlNode, prefix, prefixOverrides, suffix, suffixOverrides);
            targetContents.add(trim);
        }
    }

    private class WhereHandler implements NodeHandler {
        public WhereHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            List<SqlNode> contents = parseDynamicTags(nodeToHandle);
            MixedSqlNode mixedSqlNode = new MixedSqlNode(contents);
            WhereSqlNode where = new WhereSqlNode(configuration, mixedSqlNode);
            targetContents.add(where);
        }
    }

    private class SetHandler implements NodeHandler {
        public SetHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            List<SqlNode> contents = parseDynamicTags(nodeToHandle);
            MixedSqlNode mixedSqlNode = new MixedSqlNode(contents);
            SetSqlNode set = new SetSqlNode(configuration, mixedSqlNode);
            targetContents.add(set);
        }
    }

    private class ForEachHandler implements NodeHandler {
        public ForEachHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            List<SqlNode> contents = parseDynamicTags(nodeToHandle);
            MixedSqlNode mixedSqlNode = new MixedSqlNode(contents);
            String collection = nodeToHandle.getStringAttribute("collection");
            String item = nodeToHandle.getStringAttribute("item");
            String index = nodeToHandle.getStringAttribute("index");
            String open = nodeToHandle.getStringAttribute("open");
            String close = nodeToHandle.getStringAttribute("close");
            String separator = nodeToHandle.getStringAttribute("separator");
            ForEachSqlNode forEachSqlNode = new ForEachSqlNode(configuration, mixedSqlNode, collection, index, item, open, close, separator);
            targetContents.add(forEachSqlNode);
        }
    }

    /**
     * if 节点
     */
    private class IfHandler implements NodeHandler {
        public IfHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            /**
             * 当然,所有的动态语句都是可嵌套的, 所以对于子节点,要循环调用parseDynamicTags.
             */
            List<SqlNode> contents = parseDynamicTags(nodeToHandle);//先处理子节点
            MixedSqlNode mixedSqlNode = new MixedSqlNode(contents);//将子节点封装为一个MixedSqlNode

            //然后再处理节点本身, 对于节点本身来说,惟一重要的属性就是test
            String test = nodeToHandle.getStringAttribute("test");
            //创建一个IfSqlNode
            IfSqlNode ifSqlNode = new IfSqlNode(mixedSqlNode, test);
            targetContents.add(ifSqlNode);
        }
    }

    private class OtherwiseHandler implements NodeHandler {
        public OtherwiseHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            List<SqlNode> contents = parseDynamicTags(nodeToHandle);
            MixedSqlNode mixedSqlNode = new MixedSqlNode(contents);
            targetContents.add(mixedSqlNode);
        }
    }

    private class ChooseHandler implements NodeHandler {
        public ChooseHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            List<SqlNode> whenSqlNodes = new ArrayList<SqlNode>();
            List<SqlNode> otherwiseSqlNodes = new ArrayList<SqlNode>();
            handleWhenOtherwiseNodes(nodeToHandle, whenSqlNodes, otherwiseSqlNodes);
            SqlNode defaultSqlNode = getDefaultSqlNode(otherwiseSqlNodes);
            ChooseSqlNode chooseSqlNode = new ChooseSqlNode(whenSqlNodes, defaultSqlNode);
            targetContents.add(chooseSqlNode);
        }

        private void handleWhenOtherwiseNodes(XNode chooseSqlNode, List<SqlNode> ifSqlNodes, List<SqlNode> defaultSqlNodes) {
            List<XNode> children = chooseSqlNode.getChildren();
            for (XNode child : children) {
                String nodeName = child.getNode().getNodeName();
                NodeHandler handler = nodeHandlers(nodeName);
                if (handler instanceof IfHandler) {
                    handler.handleNode(child, ifSqlNodes);
                } else if (handler instanceof OtherwiseHandler) {
                    handler.handleNode(child, defaultSqlNodes);
                }
            }
        }

        private SqlNode getDefaultSqlNode(List<SqlNode> defaultSqlNodes) {
            SqlNode defaultSqlNode = null;
            if (defaultSqlNodes.size() == 1) {
                defaultSqlNode = defaultSqlNodes.get(0);
            } else if (defaultSqlNodes.size() > 1) {
                throw new BuilderException("Too many default (otherwise) elements in choose statement.");
            }
            return defaultSqlNode;
        }
    }

}
