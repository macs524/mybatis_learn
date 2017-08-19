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
package org.apache.ibatis.reflection.property;

import java.util.Iterator;

/**
 * @author Clinton Begin
 */
public class PropertyTokenizer implements Iterator<PropertyTokenizer> {
  private String name;
  private final String indexedName;
  private String index;
  private final String children;

    /**
     * 对属性表达式进行解析
     * @param fullname fullname
     */
  public PropertyTokenizer(String fullname) {

      //假设待处理的表达式是item[0].order[0].data
    int delim = fullname.indexOf('.'); // 首先判断是否有".",  如果没有"."
      // 比如像theName, theName[1], theName['abc'], 这类形式
      // 则name=fullname, children = 0.
      // 如果有., 则第一个点之前的为name, 如本例name='item[0]', children = 'order[0].data'.
    if (delim > -1) {
      name = fullname.substring(0, delim);
      children = fullname.substring(delim + 1); //如果children不等于NULL, 则hasNext()为true
        //也就是说,只要有".", 那么就有next.
    } else {
      name = fullname;
      children = null;
    }


    indexedName = name; // indexedName, 表示未处理[]之前的
      // 比如本例中,它就应该是'item[0]'.

    delim = name.indexOf('['); // 判断是否有[]
    if (delim > -1) {
        // 这个是假设在正常情况下,[]是成对出现的,且]为name的最后一个字符, 正常情况下也应该是这样.
        // 那么这样处理之后, index 相当于是[]中间的部分'0', 当然也可能是一个其它的字符串
        // 而name 则是去掉[]之后的值, 为'item'.
      index = name.substring(delim + 1, name.length() - 1);
      name = name.substring(0, delim);
    }
  }

  public String getName() {
    return name;
  }

  public String getIndex() {
    return index;
  }

  public String getIndexedName() {
    return indexedName;
  }

  public String getChildren() {
    return children;
  }

  @Override
  public boolean hasNext() {
    return children != null;
  }

  @Override
  public PropertyTokenizer next() {
      // 在使用之前一定要判断hasNext(), 要不然如果children是null, 就出问题了.
    return new PropertyTokenizer(children); // 继续处理childeren.
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException("Remove is not supported, as it has no meaning in the context of properties.");
  }
}
