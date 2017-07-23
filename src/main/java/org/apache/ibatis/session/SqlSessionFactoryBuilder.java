package org.apache.ibatis.session;

import org.apache.ibatis.builder.xml.XMLConfigBuilder;
import org.apache.ibatis.exceptions.ExceptionFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

/**
 * SqlSessionFactoryBuilder 这个作用是为了构造SqlSessionFactory.
 * Builds {@link SqlSession} instances.
 *
 * @author Clinton Begin
 */
public class SqlSessionFactoryBuilder {

  public SqlSessionFactory build(Reader reader) {
    return build(reader, null, null);
  }

  public SqlSessionFactory build(Reader reader, String environment) {
    return build(reader, environment, null);
  }

  public SqlSessionFactory build(Reader reader, Properties properties) {
    return build(reader, null, properties);
  }

  public SqlSessionFactory build(Reader reader, String environment, Properties properties) {
     return build(new XMLConfigBuilder(reader, environment, properties), reader);
  }

  public SqlSessionFactory build(InputStream inputStream) {
    return build(inputStream, null, null);
  }

  public SqlSessionFactory build(InputStream inputStream, String environment) {
    return build(inputStream, environment, null);
  }

  public SqlSessionFactory build(InputStream inputStream, Properties properties) {
    return build(inputStream, null, properties);
  }

  public SqlSessionFactory build(InputStream inputStream, String environment, Properties properties) {
    return build(new XMLConfigBuilder(inputStream, environment, properties), inputStream);
  }

  public SqlSessionFactory build(XMLConfigBuilder configBuilder, Closeable closeable) {
    try {
      return build(configBuilder.parse()); //解析出Configuration参数.
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error building SqlSession.", e);
    } finally {
      ErrorContext.instance().reset();
      try {
        closeable.close();
      } catch (IOException e) {
        // Intentionally ignore. Prefer previous error.
      }
    }
  }

  /**
   * 这个方法最重要，前面所有的重载方法都会调用过来，以创建出SqlSessionFactory的默认实现
   * @param config config
   * @return 结果
   */
  public SqlSessionFactory build(Configuration config) {
      //也就是说,初始化后,mysql中的所有配置信息都到了config里,那么可以顺序创建SessionFactory了.
    return new DefaultSqlSessionFactory(config);
  }

}
