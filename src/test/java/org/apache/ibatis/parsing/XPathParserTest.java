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

import org.apache.ibatis.io.Resources;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;

public class XPathParserTest {

  @Test
  public void shouldTestXPathParserMethods() throws Exception {
    String resource = "resources/nodelet_test.xml";
    InputStream inputStream = Resources.getResourceAsStream(resource);
    XPathParser parser = new XPathParser(inputStream, false, null, null);

      //按绝对路径解析.
    assertEquals((Long)1970l, parser.evalLong("/employee/birth_date/year"));
    assertEquals((short) 6, (short) parser.evalShort("/employee/birth_date/month"));
    assertEquals((Integer) 15, parser.evalInteger("/employee/birth_date/day"));
    assertEquals((Float) 5.8f, parser.evalFloat("/employee/height"));
    assertEquals((Double) 5.8d, parser.evalDouble("/employee/height"));

    //解析属性
    assertEquals("${id_var}", parser.evalString("/employee/@id"));
      assertEquals("jd", parser.evalString("/employee/@belong"));

    assertEquals(Boolean.TRUE, parser.evalBoolean("/employee/active"));
    assertEquals("<id>${id_var}</id>", parser.evalNode("/employee/@id").toString().trim());
    assertEquals(9, parser.evalNodes("/employee/*").size());

    XNode node = parser.evalNode("/employee/height");
    assertEquals("employee/height", node.getPath()); // 路径测试
      //取Hight节点,和父节点之间用_相连
    assertEquals("employee[${id_var}]_height", node.getValueBasedIdentifier());


      node = parser.evalNode("/employee/propTest");

      System.out.println(node.getChildrenAsProperties());


      node = parser.evalNode("/employee/select");
      System.out.println(node.getStringBody().trim());

    inputStream.close();
  }

}
