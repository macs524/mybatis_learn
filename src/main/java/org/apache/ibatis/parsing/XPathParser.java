/**
 * Copyright 2009-2017 the original author or authors.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.parsing;

import org.apache.ibatis.builder.BuilderException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.*;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Mybatis对于XML解析的封装类,封装了java本身的DOM解析逻辑.
 *
 * 这个类,主要是对于XML的原始解析进行了封装.
 *
 * 尤其是对于Node, 转化为了XNode.
 *
 * 对于XML的解析方式,使用的是java的原始dom解析方式.
 *
 * 另外对于其它类型的解析也进行了一些封装.
 *
 * 当创建这个Parser时,整个document就会被解析完成,所以xml不需要解析多次.
 *
 * 一个XPathParser对应一个XML文件的解析结果.
 *
 * @author Clinton Begin
 */
public class XPathParser {

    /**
     * 解析后的文档
     */
    private final Document document;
    /**
     * 是否校验
     */
    private boolean validation;
    /**
     * DTD文件的加载策略
     */
    private EntityResolver entityResolver;
    /**
     * mybatis-config中的配置属性, 这个主要用来做变量替换
     */
    private Properties variables;
    /**
     * xml的路径表达式对象,通过这个进行XML节点查找
     */
    private XPath xpath;

    //--------- 以下为构造函数 , 其实最终各方法的处理结果也只有一个最终的处理方法 --------------
    public XPathParser(String xml) {
        commonConstructor(false, null, null);
        this.document = createDocument(new InputSource(new StringReader(xml)));
    }

    public XPathParser(Reader reader) {
        commonConstructor(false, null, null);
        this.document = createDocument(new InputSource(reader));
    }

    public XPathParser(InputStream inputStream) {
        commonConstructor(false, null, null);
        this.document = createDocument(new InputSource(inputStream));
    }

    public XPathParser(Document document) {
        commonConstructor(false, null, null);
        this.document = document;
    }

    public XPathParser(String xml, boolean validation) {
        commonConstructor(validation, null, null);
        this.document = createDocument(new InputSource(new StringReader(xml)));
    }

    public XPathParser(Reader reader, boolean validation) {
        commonConstructor(validation, null, null);
        this.document = createDocument(new InputSource(reader));
    }

    public XPathParser(InputStream inputStream, boolean validation) {
        commonConstructor(validation, null, null);
        this.document = createDocument(new InputSource(inputStream));
    }

    public XPathParser(Document document, boolean validation) {
        commonConstructor(validation, null, null);
        this.document = document;
    }

    public XPathParser(String xml, boolean validation, Properties variables) {
        commonConstructor(validation, variables, null);
        this.document = createDocument(new InputSource(new StringReader(xml)));
    }

    public XPathParser(Reader reader, boolean validation, Properties variables) {
        commonConstructor(validation, variables, null);
        this.document = createDocument(new InputSource(reader));
    }

    public XPathParser(InputStream inputStream, boolean validation, Properties variables) {
        commonConstructor(validation, variables, null);
        this.document = createDocument(new InputSource(inputStream));
    }

    public XPathParser(Document document, boolean validation, Properties variables) {
        commonConstructor(validation, variables, null);
        this.document = document;
    }

    public XPathParser(String xml, boolean validation, Properties variables, EntityResolver entityResolver) {
        commonConstructor(validation, variables, entityResolver);
        this.document = createDocument(new InputSource(new StringReader(xml)));
    }

    public XPathParser(Reader reader, boolean validation, Properties variables, EntityResolver entityResolver) {
        commonConstructor(validation, variables, entityResolver);
        this.document = createDocument(new InputSource(reader));
    }

    /**
     * 初始化XPath解析器
     *
     * 主要做两件事
     * 1) 设置属性
     * 2) 解析文档
     * 很显然,第二步是主要工作, 负责把XML资源解析为一个Document对象
     * 这样就帮我们封装了对于XML文件的解析过程.
     *
     * 一个常见的调用方式是
     * new XPathParser(inputStream, true, props, new XMLMapperEntityResolver().
     * 而在解析config.xml时, 通常props是null.
     * 所以在mybatis内部使用时, 几个属性如下:
     * validation --> false
     * entityResolver --> XMLMapperEntityResolver
     * variables --> null
     * xpath --> XPathFactory.newInstance().newXPath().  这个是估定的解析方式.
     *
     * @param inputStream XML文件对象的输入流
     * @param validation 是否验证
     * @param variables 解析时的属性变量
     * @param entityResolver DTD 文件加载方式
     */
    public XPathParser(InputStream inputStream, boolean validation, Properties variables, EntityResolver entityResolver) {
        //1. 设置属性
        commonConstructor(validation, variables, entityResolver);
        //2. 解析文档
        this.document = createDocument(new InputSource(inputStream));
    }

    public XPathParser(Document document, boolean validation, Properties variables, EntityResolver entityResolver) {
        commonConstructor(validation, variables, entityResolver);
        this.document = document;
    }

    //---------- 构造函数 end -------------


    //设置配置变量
    public void setVariables(Properties variables) {
        this.variables = variables;
    }


    //以下是对于表达式解析的封装.
    //---- string ---
    public String evalString(String expression) {
        //解析对象是文档,因为文档是这个类的属性, 所以不需要显示提供.
        return evalString(document, expression);
    }

    /**
     * 在某个节点或者文档上解析指定表达式
     * @param root 节点或者文档
     * @param expression xpath表达式
     * @return 解析之后的结果
     */
    public String evalString(Object root, String expression) {
        String result = (String) evaluate(expression, root, XPathConstants.STRING);

        //对于string类的值,要进行参数替换
        return PropertyParser.parse(result, variables);
    }

    // ---- boolean,有对应的boolean类型 ---
    public Boolean evalBoolean(String expression) {
        return evalBoolean(document, expression);
    }

    public Boolean evalBoolean(Object root, String expression) {
        return (Boolean) evaluate(expression, root, XPathConstants.BOOLEAN);
    }

    // ---- short , 由于没有short类型,所以将其作为string类型解析后再转换
    public Short evalShort(String expression) {
        return evalShort(document, expression);
    }

    public Short evalShort(Object root, String expression) {
        return Short.valueOf(evalString(root, expression));
    }


    // ---- integer , 同样,没有int, 通过string来转换
    public Integer evalInteger(String expression) {
        return evalInteger(document, expression);
    }

    public Integer evalInteger(Object root, String expression) {
        return Integer.valueOf(evalString(root, expression));
    }

    //------ long ---------
    public Long evalLong(String expression) {
        return evalLong(document, expression);
    }

    public Long evalLong(Object root, String expression) {
        return Long.valueOf(evalString(root, expression));
    }

    //------ float ---------
    public Float evalFloat(String expression) {
        return evalFloat(document, expression);
    }

    public Float evalFloat(Object root, String expression) {
        return Float.valueOf(evalString(root, expression));
    }

    //------ double , 这个有直接的number 类型, 所以不需要用string来转换 ---------
    public Double evalDouble(String expression) {
        return evalDouble(document, expression);
    }

    public Double evalDouble(Object root, String expression) {
        return (Double) evaluate(expression, root, XPathConstants.NUMBER);
    }


    //解析节点列表
    public List<XNode> evalNodes(String expression) {
        return evalNodes(document, expression);
    }

    public List<XNode> evalNodes(Object root, String expression) {
        List<XNode> xnodes = new ArrayList<XNode>();
        //这是原始节点
        NodeList nodes = (NodeList) evaluate(expression, root, XPathConstants.NODESET);
        for (int i = 0; i < nodes.getLength(); i++) {

            //原始节点转化为xnode.
            xnodes.add(new XNode(this, nodes.item(i), variables));
        }
        return xnodes;
    }

    /**
     * 从document中解析指定表达式的节点，并返回XNode
     * @param expression 表达式
     * @return 解析后的XNODE
     */
    public XNode evalNode(String expression) {
        return evalNode(document, expression);
    }

    /**
     * 解析节点时,其variables即为解析器自身的variables
     * 而自身的参数又从何而来?
     * @param root
     * @param expression
     * @return
     */
    public XNode evalNode(Object root, String expression) {
        //具体的解析方式我们不深入研究，我们只研究Mybatis对于节点的定义
        Node node = (Node) evaluate(expression, root, XPathConstants.NODE);
        if (node == null) {
            return null;
        }
        //也就是说,其实所有的XNode,其对应的xpathParser都只有一份.而且是同一份,所以如果其属性有变化,会立即感知.
        return new XNode(this, node, variables);
    }

    /**
     * 一个简单的封装,最终是调用了xpath的相关方法
     * @param expression 表达式
     * @param root 目标节点
     * @param returnType 返回类型
     * @return 返回对应的数据
     */
    private Object evaluate(String expression, Object root, QName returnType) {
        try {
            return xpath.evaluate(expression, root, returnType);
        } catch (Exception e) {
            throw new BuilderException("Error evaluating XPath.  Cause: " + e, e);
        }
    }

    /**
     * 将目标文件流转化为Document，完成对于XML的解析。
     * 这一段代码跟mybatis没有多少关系,就是对于一个普通xml文件的解析 .使用JAVA自身提供的类
     * @param inputSource 待解析的对象
     * @return 解析后的文档
     */
    private Document createDocument(InputSource inputSource) {
        // important: this must only be called AFTER common constructor
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setValidating(validation);


            //通用的配置
            factory.setNamespaceAware(false);
            factory.setIgnoringComments(true);
            factory.setIgnoringElementContentWhitespace(false);
            factory.setCoalescing(false);
            factory.setExpandEntityReferences(true);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(entityResolver);
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void warning(SAXParseException exception) throws SAXException {
                }
            });
            return builder.parse(inputSource); //把字符或字节流解析成为一个Document对象
        } catch (Exception e) {
            throw new BuilderException("Error creating document instance.  Cause: " + e, e);
        }
    }

    /**
     * 设置属性，校验规则，XPATH处理器
     * @param validation 是否校验, 通常这个会校验
     * @param variables 属性变量
     * @param entityResolver  entityResolver ，这个的主要作用是给定一个publicId和SystemId，用于查找其对应的配置文件
     */
    private void commonConstructor(boolean validation, Properties variables, EntityResolver entityResolver) {
        this.validation = validation;
        this.entityResolver = entityResolver;
        this.variables = variables;
        this.xpath = XPathFactory.newInstance().newXPath();
    }

}
