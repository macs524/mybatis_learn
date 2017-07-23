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
package org.apache.ibatis.scripting.xmltags;

/**
 * @author Clinton Begin
 */
public class IfSqlNode implements SqlNode {
  private final ExpressionEvaluator evaluator;
  private final String test;
  private final SqlNode contents;

  public IfSqlNode(SqlNode contents, String test) {
    this.test = test;
    this.contents = contents;
    this.evaluator = new ExpressionEvaluator();
  }

    /**
     * 重点在于这部分
     * @param context
     * @return
     */
  @Override
  public boolean apply(DynamicContext context) {
      //test是一个表达式,比如 includeLastName != null
      //所以evaluator的作用很显然,就是根据参数,分析 includeLastName != null 是否成立
    if (evaluator.evaluateBoolean(test, context.getBindings())) {

        //如果满足条件 , 则执行其子节点的apply语句.
      contents.apply(context);
      return true;
    }
    return false;
  }

}
