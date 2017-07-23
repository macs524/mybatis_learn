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

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

  private final Configuration configuration;
  private final MapperBuilderAssistant builderAssistant;

  public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
    this.configuration = configuration;
    this.builderAssistant = builderAssistant;
  }

  public void applyIncludes(Node source) {
    Properties variablesContext = new Properties();
    Properties configurationVariables = configuration.getVariables();
    if (configurationVariables != null) {
      variablesContext.putAll(configurationVariables);
    }
    applyIncludes(source, variablesContext, false);
  }

  /**
   * 解析include节点,这是个很重要的方法
   * Recursively apply includes through all SQL fragments.
   * @param source Include node in DOM tree
   * @param variablesContext Current context for static variables with values
   * @param included 是否是include节点?
   */
  private void applyIncludes(Node source, final Properties variablesContext, boolean included) {

      //刚开始执行的时候 , source应该是insert/update/select/delete之一
      //比如说select, 那么肯定会走另外一个分支
    if (source.getNodeName().equals("include")) {

        //如果节点是include, 则类似下面的代码
        /*<include refid="byBlogId">
            <property name="prefix" value="blog"/>
            </include>
            所以, 其refid值为byBlogId
            那么, 文件中应该有<sql id='byBlgoId' /> 这样的节点,
            并且通过之前的解析,已经存到了sqlFragment中去了.

            通过下面的方法得到一个克隆之后的待include的节点.
            本例中也就是<sql id='byBlogId' />这个节点. (toInclude)
        */
      Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);

        // 这里的source指include节点, 为什么会执行这个方法呢?
      Properties toIncludeContext = getVariablesContext(source, variablesContext);

        //再对sql节点里的include进行解析. 这个时候, 注意第三个参数是true.惟一要表达的,应该就是说这是一个sql节点吧.
        //因为sql节点的子节点相对比较灵活,当然也可以有include了.

      applyIncludes(toInclude, toIncludeContext, true);

        //如果两个不属于同一个文档?
      if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
        toInclude = source.getOwnerDocument().importNode(toInclude, true);
      }

        //可以认为是用<sql id=xxx /> 替换了 <include refid=xxx />
      source.getParentNode().replaceChild(toInclude, source);

        //那么把toInclude中的子节点依次移出来,添加到toInclude前面.
        //比如 <sql id="columns">id, title </sql>
        //在移动之后,就会变成 id, title <sql id="columns"></sql>

      while (toInclude.hasChildNodes()) {
        toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
      }
        //最后再将本身删除掉,这是水到渠成的.
      toInclude.getParentNode().removeChild(toInclude);
    } else if (source.getNodeType() == Node.ELEMENT_NODE) {

        //如果节点是element, 且不是include, 则找其子节点继续解析.
      NodeList children = source.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        applyIncludes(children.item(i), variablesContext, included);
      }
    } else if (included && source.getNodeType() == Node.TEXT_NODE
        && !variablesContext.isEmpty()) {
      // replace variables ins all text nodes不
        //但如果就是普通的文本节点了,比如说select * from xxx 之类的呢?
        //如果不是include,不解析.也就是不替换变量

        //从前面的分析看, 当节点为sql节点时, 对于其中的文本节点需要进行变量替换
        //那么问题来了,为什么节点为select节点时,其文本不需替换呢?也许是不在这里替换吧.
      source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
    }
  }

  private Node findSqlFragment(String refid, Properties variables) {
      //由于refid是从原始Node中解析出来的,而不是从XNode里,所以需要进行变量替换.
    refid = PropertyParser.parse(refid, variables);
    refid = builderAssistant.applyCurrentNamespace(refid, true);
    try {
        //正常来说一定是可以取到的,如果取不到,那么由异常来处理好了.
      XNode nodeToInclude = configuration.getSqlFragments().get(refid);
      return nodeToInclude.getNode().cloneNode(true);
    } catch (IllegalArgumentException e) {
      throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
    }
  }

  private String getStringAttribute(Node node, String name) {
    return node.getAttributes().getNamedItem(name).getNodeValue();
  }

  /**
   * Read placeholders and their values from include node definition. 
   * @param node Include node instance, 注意这hfd的node应该是include 节点
   * @param inheritedVariablesContext Current context used for replace variables in new variables values
   * @return variables context from include instance (no inherited values)
   */
  private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
    Map<String, String> declaredProperties = null;

      //找其所有子节点,因为子节点全部是属性节点.
    NodeList children = node.getChildNodes();

      //对于一个include节点来说, 其可以包含property子节点, 提供参数名及对应的值,所以我们这里要把它解析出来.
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        String name = getStringAttribute(n, "name"); //取其name
        // Replace variables inside, 取其转化后的变量值
        String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
        if (declaredProperties == null) {
          declaredProperties = new HashMap<String, String>();
        }
        if (declaredProperties.put(name, value) != null) {
          throw new BuilderException("Variable " + name + " defined twice in the same include definition");
        }
      }
    }
    if (declaredProperties == null) {
      return inheritedVariablesContext;
    } else {
      Properties newProperties = new Properties();
      newProperties.putAll(inheritedVariablesContext);
      newProperties.putAll(declaredProperties);

        //返回一个增强版本的属性对象,即包括之前的,也包括<include>节点里新增的.
      return newProperties;
    }
  }
}
