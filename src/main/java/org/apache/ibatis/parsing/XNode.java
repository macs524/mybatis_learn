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
package org.apache.ibatis.parsing;

import org.w3c.dom.CharacterData;
import org.w3c.dom.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 可以理解为XNode是原始Node的一种增强。
 *
 * 在原始Node上，其还包括了如下一些功能：
 *
 * 1）包括对原始节点的引用
 * 2）存储了节点名
 * 3）存储了节点中的文本内容（如果有的话）
 * 4）存储了节点的属性
 * 5）包括一个解析器的引用
 * 6）包括原始配置数据的引用
 *
 * 那这种节点一方面更强大，另一方面操作起来也更方便
 *
 * @author Clinton Begin
 */
public class XNode {

  private final Node node;
  private final String name;
  private final String body;
  private final Properties attributes;
  private final Properties variables;
  private final XPathParser xpathParser;

  /**
   * 惟一的构造函数
   * @param xpathParser 解析器
   * @param node 节点
   * @param variables //初始化时的属性配置，可以认为是NULL
   */
  public XNode(XPathParser xpathParser, Node node, Properties variables) {
    this.xpathParser = xpathParser;
    this.node = node;
    this.name = node.getNodeName(); //节点名
    this.variables = variables;
    this.attributes = parseAttributes(node); //存储其属性节点中的各个属性名和属性值
    this.body = parseBody(node); //存储其子节点的文本值，如果有的话。
  }

    /**
     * 将一个普通的NODE转化为XNODE
     * @param node node
     * @return 结果
     */
  public XNode newXNode(Node node) {
    return new XNode(xpathParser, node, variables);
  }

    /**
     * 查找当前结点的父节点
     * @return 父节点
     */
  public XNode getParent() {
    Node parent = node.getParentNode();
    if (parent == null || !(parent instanceof Element)) {
      return null;
    } else {
        return newXNode(parent);
      //return new XNode(xpathParser, parent, variables);
    }
  }

    /**
     * 查找当前节点的完全路径.
     * @return 路径
     */
  public String getPath() {
    StringBuilder builder = new StringBuilder();
    Node current = node;
    while (current != null && current instanceof Element) {
      if (current != node) {
        builder.insert(0, "/"); // 当前节点不添加 /
      }
      builder.insert(0, current.getNodeName());
      current = current.getParentNode();
    }
    return builder.toString();
  }

    /**
     * 这个和getPath有点像,只是节点之前不再是用/, 而是用_
     * 另外, 如果某个节点有id/value/property 三者之一的属性, 用[]括起来表示.
     * @return 路径值.
     */
  public String getValueBasedIdentifier() {
    StringBuilder builder = new StringBuilder();
    XNode current = this;
    while (current != null) {
      if (current != this) {
        builder.insert(0, "_");
      }

        //三个特殊的属性 id/value/property
      String value = current.getStringAttribute("id",
          current.getStringAttribute("value",
              current.getStringAttribute("property", null)));
      if (value != null) {
          // 如果有ID, 将ID用[]存起来
        value = value.replace('.', '_');
        builder.insert(0, "]");
        builder.insert(0,
            value);
        builder.insert(0, "[");
      }
      builder.insert(0, current.getName()); // 当前节点的名称
      current = current.getParent();
    }
    return builder.toString();
  }

  public String evalString(String expression) {
    return xpathParser.evalString(node, expression);
  }

  public Boolean evalBoolean(String expression) {
    return xpathParser.evalBoolean(node, expression);
  }

  public Double evalDouble(String expression) {
    return xpathParser.evalDouble(node, expression);
  }

  public List<XNode> evalNodes(String expression) {
    return xpathParser.evalNodes(node, expression);
  }

  public XNode evalNode(String expression) {
      //一个简单封装
    return xpathParser.evalNode(node, expression);
  }

  public Node getNode() {
    return node;
  }

  public String getName() {
    return name;
  }

  public String getStringBody() {
    return getStringBody(null);
  }

  public String getStringBody(String def) {
    if (body == null) {
      return def;
    } else {
      return body;
    }
  }

  public Boolean getBooleanBody() {
    return getBooleanBody(null);
  }

  public Boolean getBooleanBody(Boolean def) {
    if (body == null) {
      return def;
    } else {
      return Boolean.valueOf(body);
    }
  }

  public Integer getIntBody() {
    return getIntBody(null);
  }

  public Integer getIntBody(Integer def) {
    if (body == null) {
      return def;
    } else {
      return Integer.parseInt(body);
    }
  }

  public Long getLongBody() {
    return getLongBody(null);
  }

  public Long getLongBody(Long def) {
    if (body == null) {
      return def;
    } else {
      return Long.parseLong(body);
    }
  }

  public Double getDoubleBody() {
    return getDoubleBody(null);
  }

  public Double getDoubleBody(Double def) {
    if (body == null) {
      return def;
    } else {
      return Double.parseDouble(body);
    }
  }

  public Float getFloatBody() {
    return getFloatBody(null);
  }

  public Float getFloatBody(Float def) {
    if (body == null) {
      return def;
    } else {
      return Float.parseFloat(body);
    }
  }

  public <T extends Enum<T>> T getEnumAttribute(Class<T> enumType, String name) {
    return getEnumAttribute(enumType, name, null);
  }

  public <T extends Enum<T>> T getEnumAttribute(Class<T> enumType, String name, T def) {
    String value = getStringAttribute(name);
    if (value == null) {
      return def;
    } else {
      return Enum.valueOf(enumType, value);
    }
  }

  public String getStringAttribute(String name) {
    return getStringAttribute(name, null);
  }

  /**
   * 由于attributes中存储了节点本身的属性信息，所以取某个属性的值就变得非常简单了。
   * @param name 属性
   * @param def 默认值
   * @return 结果
   */
  public String getStringAttribute(String name, String def) {
    String value = attributes.getProperty(name);
    if (value == null) {
      return def;
    } else {
      return value;
    }
  }

  public Boolean getBooleanAttribute(String name) {
    return getBooleanAttribute(name, null);
  }

  public Boolean getBooleanAttribute(String name, Boolean def) {
    String value = attributes.getProperty(name);
    if (value == null) {
      return def;
    } else {
      return Boolean.valueOf(value);
    }
  }

  public Integer getIntAttribute(String name) {
    return getIntAttribute(name, null);
  }

  public Integer getIntAttribute(String name, Integer def) {
    String value = attributes.getProperty(name);
    if (value == null) {
      return def;
    } else {
      return Integer.parseInt(value);
    }
  }

  public Long getLongAttribute(String name) {
    return getLongAttribute(name, null);
  }

  public Long getLongAttribute(String name, Long def) {
    String value = attributes.getProperty(name);
    if (value == null) {
      return def;
    } else {
      return Long.parseLong(value);
    }
  }

  public Double getDoubleAttribute(String name) {
    return getDoubleAttribute(name, null);
  }

  public Double getDoubleAttribute(String name, Double def) {
    String value = attributes.getProperty(name);
    if (value == null) {
      return def;
    } else {
      return Double.parseDouble(value);
    }
  }

  public Float getFloatAttribute(String name) {
    return getFloatAttribute(name, null);
  }

  public Float getFloatAttribute(String name, Float def) {
    String value = attributes.getProperty(name);
    if (value == null) {
      return def;
    } else {
      return Float.parseFloat(value);
    }
  }

    /**
     * 查找访节点的所有一级子节点
     * @return 子节点列表
     */
  public List<XNode> getChildren() {
    List<XNode> children = new ArrayList<XNode>();
    NodeList nodeList = node.getChildNodes();
    if (nodeList != null) {
      for (int i = 0, n = nodeList.getLength(); i < n; i++) {
        Node node = nodeList.item(i);
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            // 如果节点是元素类型,则将其转化为子节点.
            children.add(newXNode(node));
          //children.add(new XNode(xpathParser, node, variables));
        }
      }
    }
    return children;
  }

  /**
   * 将子节点转化为属性对象, 本身是一个Hashtable.
   * 这个适用的场景是某个节点下的子节点都是属性节点,示例如下:
   *
   * <bean id='xxx' class='com.jd.macs.XxxBean'>
   *     <property name='id' value='def' />
   *     <property name='name' value='xxxName' />
   * </bean>
   *
   * 但是要注意的是, 从方法的实现上看, name和value是取的该节点的属性.
   * 对于<property name='theName'>theVal</property>来说,
   * theVal 相当于是一个类型为TEXT的子节点,而不是其父节点的value属性.
   * 所以, 这种情况下, 这样配置是有问题的,不会被正确解析为一个属性对.
   * 所以, 在mybatis的属性配置中,一定要将value作为属性去配置,而不是作为值.
   * @return 解析后的结果
   */
  public Properties getChildrenAsProperties() {
    Properties properties = new Properties();
    for (XNode child : getChildren()) {

        //作为一个XNode节点来说, 根据前面的分析, 其属性值是经过解析之后的.
        //所以这里的name和value, 如果其值里带有${},那么一定是取经过变量替换之后的值.
      String name = child.getStringAttribute("name");
      String value = child.getStringAttribute("value");
      if (name != null && value != null) {
        properties.setProperty(name, value);
      }
    }
    return properties;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("<");
    builder.append(name);
    for (Map.Entry<Object, Object> entry : attributes.entrySet()) {
      builder.append(" ");
      builder.append(entry.getKey());
      builder.append("=\"");
      builder.append(entry.getValue());
      builder.append("\"");
    }
    List<XNode> children = getChildren();
    if (!children.isEmpty()) {
      builder.append(">\n");
      for (XNode node : children) {
        builder.append(node.toString());
      }
      builder.append("</");
      builder.append(name);
      builder.append(">");
    } else if (body != null) {
      builder.append(">");
      builder.append(body);
      builder.append("</");
      builder.append(name);
      builder.append(">");
    } else {
      builder.append("/>");
    }
    builder.append("\n");
    return builder.toString();
  }

  /**
   * 将一个节点的属性节点转化为Properties对象.
   * @param n 节点
   * @return 属性集
   */
  private Properties parseAttributes(Node n) {
    Properties attributes = new Properties();

    //将属性节点转化为属性集
    NamedNodeMap attributeNodes = n.getAttributes();
    if (attributeNodes != null) {
      for (int i = 0; i < attributeNodes.getLength(); i++) {
        Node attribute = attributeNodes.item(i); //每一个属性都是一个属性节点，如 <xx a="a" b="b" />，这里的a和b就是两个属性节点。

        String value = PropertyParser.parse(attribute.getNodeValue(), variables);
        attributes.put(attribute.getNodeName(), value); //所以这里存储的是解析之后的值
      }
    }
    return attributes;
  }

  /**
   * 解析节点中的内容， 只处理某个节点中的文本内容
   * 假设某个节点表示<name>Apple</name>
   * 则当前节点为<name> , 子节点为text node，值为Apple，
   * 所以parseBody的话，则返回的应该是Apple.
   *
   * 另外这个是不递归的，只要找到就返回，所以对于
   * <select id = ''>
   *     SELECT * from
   *     <if test='tableName != null' >
   *         ${tableName}
   *     </if>
   *     where id = 0
   * </select>
   * 其select节点的body值为SELECT * from ，而不包括 where id = 0.
   * @param node
   * @return
   */
  private String parseBody(Node node) {
    String data = getBodyData(node);
    if (data == null) {
      NodeList children = node.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        Node child = children.item(i);
        data = getBodyData(child);
        if (data != null) {
          break;
        }
      }
    }
    return data;
  }


  /**
   * 只处理节点为文本和CDATA的节点
   * @param child 子节点
   * @return 结果
   */
  private String getBodyData(Node child) {
    if (child.getNodeType() == Node.CDATA_SECTION_NODE
        || child.getNodeType() == Node.TEXT_NODE) {
      String data = ((CharacterData) child).getData();
      data = PropertyParser.parse(data, variables);
      return data;
    }
    return null;
  }

}
/*
* XNode 也就算是分析完了.
*
* 这个节点的主要功能如下:
*
* 1) 对原生节点进行包装, 计算其属性及节点体的内容
* 2) 对父子节点进行封装为XNode
* 3) 将子节点转化为Property
* 4) 将某个节点转化为Property.
* 5) 以该节点作为根节点, 对xpath表达式进行计算.
*
*
* */