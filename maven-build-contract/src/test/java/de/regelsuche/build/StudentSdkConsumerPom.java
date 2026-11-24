package de.regelsuche.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;

/** Binds a staged standalone consumer to the repository carried by its SDK archive. */
final class StudentSdkConsumerPom {
    private static final String NAMESPACE = "http://maven.apache.org/POM/4.0.0";

    private StudentSdkConsumerPom() {}

    static byte[] stage(Path source, String version) throws IOException {
        try {
            var root = MavenPomTestSupport.parse(source).getDocumentElement();
            StudentSdkArchive.require("project".equals(root.getLocalName())
                && NAMESPACE.equals(root.getNamespaceURI()), "invalid consumer Maven project");
            var properties = child(root, "properties");
            child(properties, "regelsuche.groupId").setTextContent("io.github.carstenartur");
            child(properties, "regelsuche.version").setTextContent(version);
            child(properties, "regelsuche.repository").setTextContent("file://${project.basedir}/../../../repository");
            var repository = child(child(root, "repositories"), "repository");
            StudentSdkArchive.require("regelsuche-sdk".equals(child(repository, "id").getTextContent()),
                "consumer must declare its SDK repository");
            child(child(repository, "snapshots"), "enabled").setTextContent(Boolean.toString(version.endsWith("-SNAPSHOT")));
            var factory = TransformerFactory.newDefaultInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var writer = factory.newTransformer();
            writer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            writer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            var output = new ByteArrayOutputStream();
            writer.transform(new DOMSource(root), new StreamResult(output));
            output.write('\n');
            return output.toByteArray();
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Cannot stage SDK consumer POM: " + source, failure);
        }
    }

    private static Element child(Element parent, String name) throws IOException {
        Element result = null;
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())
                    && NAMESPACE.equals(element.getNamespaceURI())) {
                StudentSdkArchive.require(result == null, "duplicate consumer POM element: " + name);
                result = element;
            }
        }
        StudentSdkArchive.require(result != null, "missing consumer POM element: " + name);
        return result;
    }
}
