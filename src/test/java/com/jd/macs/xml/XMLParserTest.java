package com.jd.macs.xml;

import org.apache.ibatis.io.Resources;
import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;

/**
 * Created by cdmachangsheng on 2017/7/21.
 */
public class XMLParserTest {

    @Test
    public void testParse(){

        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder builder = builderFactory.newDocumentBuilder();

            Document document = builder.parse(XMLParserTest.class.getClassLoader()
                    .getResourceAsStream("resources/simple.xml"));

            Assert.assertNotNull(document);

        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        } catch (SAXException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
