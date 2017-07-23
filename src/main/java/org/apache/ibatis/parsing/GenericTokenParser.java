/**
 *    Copyright 2009-2016 the original author or authors.
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

/**
 * 通用Token处理器
 * @author Clinton Begin
 */
public class GenericTokenParser {

  private final String openToken;
  private final String closeToken;
  private final TokenHandler handler;

    /**
     * 构造
     * @param openToken token开始标记
     * @param closeToken token结束标记
     * @param handler token处理器
     */
  public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
    this.openToken = openToken;
    this.closeToken = closeToken;
    this.handler = handler;
  }

    /**
     * 解析, 主要业务逻辑如下:
     * 1)解析text中是否存在指定的token, 什么是指定的token
     *   比如,如果 openToken='${', closeToken='}'
     *   则主要是解析text中有没有${xxx} 这样的token
     * 2) 如果有, 则将解析出来的token存储到expression变量中去,交给handler处理
     *   不同的handler, 处理方式不一样.
     *   比如,我们前面看到的变量替换,其handler的处理方式是将expression作为key,去变量集中查找是否有其对应的value.
     *   没有则原封不动的返回.
     *
     *   但对于 DynamicCheckerTokenParser 来说,只是将内部的标记置为true, 表示这是一个动态sql
     *   然而返回的却是null
     *
     * @param text
     * @return
     */
  public String parse(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    char[] src = text.toCharArray();
    int offset = 0;
    // search open token
    int start = text.indexOf(openToken, offset);
    if (start == -1) {
      return text;
    }
    final StringBuilder builder = new StringBuilder();
    StringBuilder expression = null;
    while (start > -1) {
      if (start > 0 && src[start - 1] == '\\') {
        // this open token is escaped. remove the backslash and continue.
        builder.append(src, offset, start - offset - 1).append(openToken);
        offset = start + openToken.length();
      } else {
        // found open token. let's search close token.
        if (expression == null) {
          expression = new StringBuilder();
        } else {
          expression.setLength(0);
        }
        builder.append(src, offset, start - offset);
        offset = start + openToken.length();
        int end = text.indexOf(closeToken, offset);
        while (end > -1) {
          if (end > offset && src[end - 1] == '\\') {
            // this close token is escaped. remove the backslash and continue.
            expression.append(src, offset, end - offset - 1).append(closeToken);
            offset = end + closeToken.length();
            end = text.indexOf(closeToken, offset);
          } else {
            expression.append(src, offset, end - offset);
            offset = end + closeToken.length();
            break;
          }
        }
        if (end == -1) {
          // close token was not found.
          builder.append(src, start, src.length - start);
          offset = src.length;
        } else {
          builder.append(handler.handleToken(expression.toString()));
          offset = end + closeToken.length();
        }
      }
      start = text.indexOf(openToken, offset);
    }
    if (offset < src.length) {
      builder.append(src, offset, src.length - offset);
    }
    return builder.toString();
  }
}
