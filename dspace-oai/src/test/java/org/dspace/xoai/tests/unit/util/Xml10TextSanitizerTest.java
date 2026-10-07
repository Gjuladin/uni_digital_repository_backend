/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.tests.unit.util;

import static com.lyncode.xoai.dataprovider.core.Granularity.Second;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import com.lyncode.xoai.dataprovider.xml.XmlOutputContext;
import com.lyncode.xoai.dataprovider.xml.xoai.Element;
import com.lyncode.xoai.dataprovider.xml.xoai.Metadata;
import org.dspace.xoai.util.Xml10TextSanitizer;
import org.junit.Test;
import org.w3c.dom.Document;

public class Xml10TextSanitizerTest {

    @Test
    public void preservesTextDuringXoaiXmlRoundTrip() throws Exception {
        String original = "Research & Development; literal &amp;; \uD83D\uDE00; \u0001";
        String sanitized = Xml10TextSanitizer.sanitize(original);

        Metadata metadata = new Metadata();
        Element schema = element("dc");
        Element title = element("title");
        Element language = element("none");
        Element.Field field = new Element.Field();
        field.setName("value");
        field.setValue(sanitized);
        language.getField().add(field);
        title.getElement().add(language);
        schema.getElement().add(title);
        metadata.getElement().add(schema);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        XmlOutputContext outputContext = XmlOutputContext.emptyContext(output, Second);
        metadata.write(outputContext);
        outputContext.getWriter().flush();
        outputContext.getWriter().close();

        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(output.toByteArray()));
        assertEquals("Research & Development; literal &amp;; 😀; ",
                document.getElementsByTagName("field").item(0).getTextContent());
        assertEquals("Research & Development; literal &amp;; 😀; ",
                sanitized);
    }

    @Test
    public void handlesNullAndDropsXml10InvalidCodePoints() {
        assertNull(Xml10TextSanitizer.sanitize(null));
        assertEquals("ok", Xml10TextSanitizer.sanitize("o\u000Bk"));
    }

    private Element element(String name) {
        Element element = new Element();
        element.setName(name);
        return element;
    }
}
