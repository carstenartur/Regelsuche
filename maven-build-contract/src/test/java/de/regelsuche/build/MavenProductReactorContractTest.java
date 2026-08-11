package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

class MavenProductReactorContractTest {
  private static final List<String> BASE_MODULES = List.of("regelsuche-core","regelsuche-egraph","regelsuche-search","regelsuche-validation","regelsuche-math-algorithms","regelsuche-math-jas","regelsuche-persistence","regelsuche-solver-ir","regelsuche-solver-portfolio","regelsuche-learning","regelsuche-discovery","regelsuche-experiments","regelsuche-cli","regelsuche-quality","maven-build-contract");
  private static final List<String> PRODUCT_PROFILE_MODULES = List.of("regelsuche-persistence-hibernate","regelsuche-autopilot","regelsuche-release","regelsuche-benchmarks","app");
  @Test void activeProductProfileCompletesTheJavaApplicationReactor() throws Exception { Path root=repositoryRoot(); Document parent=parse(root.resolve("pom.xml")); Element productProfile=profile(parent,"product-reactor"); assertNotNull(productProfile,"missing product-reactor Maven profile"); assertEquals("true",directChildText(directChild(productProfile,"activation"),"activeByDefault")); assertEquals(PRODUCT_PROFILE_MODULES,directChildTexts(directChild(productProfile,"modules"),"module")); Set<String> complete=new LinkedHashSet<>(BASE_MODULES); complete.addAll(PRODUCT_PROFILE_MODULES); assertEquals(BASE_MODULES.size()+PRODUCT_PROFILE_MODULES.size(),complete.size()); for(String module:complete){Path moduleRoot=root.resolve(module);Path modulePom=moduleRoot.resolve("pom.xml");assertTrue(Files.isDirectory(moduleRoot),()->"missing module directory: "+module);assertTrue(Files.isRegularFile(modulePom),()->"missing module POM: "+modulePom);Document child=parse(modulePom);Element childParent=directChild(child.getDocumentElement(),"parent");assertNotNull(childParent,()->"module has no parent: "+module);assertEquals("de.regelsuche",directChildText(childParent,"groupId"));assertEquals("regelsuche-parent",directChildText(childParent,"artifactId"));assertEquals("0.2.0-SNAPSHOT",directChildText(childParent,"version"));}}
  @Test void applicationDistributionIsCheckoutOwnedAndVersionOverrideable() throws Exception { Path root=repositoryRoot();Document parent=parse(root.resolve("pom.xml"));Document app=parse(root.resolve("app/pom.xml"));assertEquals("${project.version}",directChildText(directChild(parent.getDocumentElement(),"properties"),"releaseVersion"));assertEquals("regelsuche-${releaseVersion}",directChildText(directChild(app.getDocumentElement(),"build"),"finalName"));assertTrue(Files.isRegularFile(root.resolve("app/src/assembly/distribution.xml")));assertTrue(Files.isRegularFile(root.resolve("app/src/main/scripts/regelsuche")));assertTrue(Files.isRegularFile(root.resolve("app/src/main/scripts/regelsuche.bat")));}
  private static Element profile(Document document,String id){Element profiles=directChild(document.getDocumentElement(),"profiles");if(profiles==null)return null;for(Node node=profiles.getFirstChild();node!=null;node=node.getNextSibling()){if(node instanceof Element element&&"profile".equals(element.getLocalName())&&id.equals(directChildText(element,"id")))return element;}return null;}
  private static Path repositoryRoot(){String configured=System.getProperty("regelsuche.repositoryRoot");assertNotNull(configured,"Maven must expose maven.multiModuleProjectDirectory to tests");return Path.of(configured).toAbsolutePath().normalize();}
  private static Document parse(Path path)throws ParserConfigurationException,IOException,SAXException{DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");try(InputStream input=Files.newInputStream(path)){return factory.newDocumentBuilder().parse(input);}}
  private static Element directChild(Element parent,String localName){if(parent==null)return null;for(Node child=parent.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element element&&localName.equals(element.getLocalName()))return element;return null;}
  private static String directChildText(Element parent,String localName){Element child=directChild(parent,localName);return child==null?null:child.getTextContent().trim();}
  private static List<String> directChildTexts(Element parent,String localName){List<String> values=new ArrayList<>();assertNotNull(parent);for(Node child=parent.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element element&&localName.equals(element.getLocalName()))values.add(element.getTextContent().trim());return List.copyOf(values);}
}
