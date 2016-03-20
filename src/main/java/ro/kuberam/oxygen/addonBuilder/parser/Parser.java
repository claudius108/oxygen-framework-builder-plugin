package ro.kuberam.oxygen.addonBuilder.parser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import ro.kuberam.oxygen.addonBuilder.AddonBuilderPluginExtension;
import ro.kuberam.oxygen.addonBuilder.javafx.DialogModel;
import ro.kuberam.oxygen.addonBuilder.javafx.bridges.framework.FrameworkGeneratingBridge;
import ro.kuberam.oxygen.addonBuilder.mutations.ObserverConnection;
import ro.kuberam.oxygen.addonBuilder.oxyFormControlDescriptors.OxyEditorDescriptor;
import ro.kuberam.oxygen.addonBuilder.parser.XQuery30.ParseException;
import ro.kuberam.oxygen.addonBuilder.parser.XQuery30.XmlSerializer;
import ro.kuberam.oxygen.addonBuilder.utils.XML;

public class Parser {

	/**
	 * Logger for logging.
	 */
	private static final Logger logger = Logger.getLogger(Parser.class.getName());

	private XMLStreamWriter actionsWriter;
	private Map<String, Element> derivedActionElements = new HashMap<String, Element>();
	private ArrayList<String> actionsWithCaretContext = new ArrayList<String>();
	private ArrayList<SimpleAction> simpleActions = new ArrayList<SimpleAction>();
	private ParsingResult parsingResult;

	private static String[] actionParameters = { "name", "description", "largeIconPath", "smallIconPath",
			"accessKey", "accelerator" };
	private static Map<String, String> insertExprTargetChoiceValues = new HashMap<String, String>();
	private static Map<String, String> replaceExprTargetChoiceValues = new HashMap<String, String>();
	private static Pattern extractTemplateIdPattern = Pattern
			.compile("(ua:(get|show)-template\\(['\"])([A-Za-z_-]+)(['\"]\\)\\s*,?\\s*)");
	private static Pattern isAttributePattern = Pattern.compile("@.+$");
	private static Pattern variablePattern = Pattern.compile("(\\$)([A-Za-z_-]+)");
	private static String oxyXpathExpressionStartMarker = "oxy_xpath_start";
	private static String oxyXpathExpressionEndMarker = "oxy_xpath_end";
	private static ArrayList<String> builtinFormControlNames = new ArrayList<String>();
	private static String baseTreeGeneratorTemplate;

	static {
		insertExprTargetChoiceValues.put("after", "After");
		insertExprTargetChoiceValues.put("before", "Before");
		insertExprTargetChoiceValues.put("as first into", "Inside as first child");
		insertExprTargetChoiceValues.put("as last into", "Inside as last child");
		insertExprTargetChoiceValues.put("into", "Inside as last child");

		replaceExprTargetChoiceValues.put("node", "After");
		replaceExprTargetChoiceValues.put("value of node", "Before");

		builtinFormControlNames.add("combo");
	}

	public Parser(File addonDirectory, String frameworkId, File targetDirectory) throws Exception {
		File sourceFile = new File(addonDirectory + File.separator + "addon.xq");
		byte buffer[] = new byte[(int) sourceFile.length()];
		java.io.FileInputStream stream = new FileInputStream(sourceFile);
		stream.read(buffer);
		stream.close();

		String xqueryFrameworkDescriptorAsString = new String(buffer, System.getProperty("file.encoding"));
		xqueryFrameworkDescriptorAsString = (xqueryFrameworkDescriptorAsString.length() > 0 && xqueryFrameworkDescriptorAsString
				.charAt(0) == '\uFEFF') ? xqueryFrameworkDescriptorAsString.substring(1)
				: xqueryFrameworkDescriptorAsString;
		File frameworkDescriptor = new File(addonDirectory + File.separator + frameworkId + ".framework");

		parsingResult = new ParsingResult();

		ByteArrayOutputStream xqueryParserOutput = new ByteArrayOutputStream();
		Writer w = new OutputStreamWriter(xqueryParserOutput, "UTF-8");
		XmlSerializer s = new XmlSerializer(w);
		XQuery30 xqueryParser = new XQuery30(xqueryFrameworkDescriptorAsString, s);
		try {
			s.writeOutput("<?xml version=\"1.0\" encoding=\"UTF-8\"?" + ">");
			xqueryParser.parse_XQuery();
		} catch (ParseException pe) {
			throw new RuntimeException("ParseException while processing "
					+ xqueryFrameworkDescriptorAsString + ":\n" + xqueryParser.getErrorMessage(pe));
		} finally {
			w.close();
		}

		long start = System.nanoTime();

		XMLOutputFactory actionsOutputFactory = XMLOutputFactory.newInstance();

		ByteArrayOutputStream actionsOutput = new ByteArrayOutputStream();

		actionsWriter = actionsOutputFactory.createXMLStreamWriter(actionsOutput);

		actionsWriter.writeStartElement("action-array");

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document xqueryParserOutputDoc = db
				.parse(new ByteArrayInputStream(xqueryParserOutput.toByteArray()));
		Element xqueryParserOutputDocumentElement = xqueryParserOutputDoc.getDocumentElement();

		// process variable declarations
		StringBuilder prolog = new StringBuilder();
		prolog.append("declare namespace ua = \"http://expath.org/ns/user-agent\"; ");
		String delim = "";

		NodeList namespaceDeclElements = xqueryParserOutputDocumentElement
				.getElementsByTagName("NamespaceDecl");
		for (int i = 0, il = namespaceDeclElements.getLength(); i < il; i++) {
			prolog.append(delim).append(((Element) namespaceDeclElements.item(i)).getTextContent())
					.append("; ");
			delim = "";
		}

		delim = "declare ";
		NodeList varDeclElements = xqueryParserOutputDocumentElement.getElementsByTagName("VarDecl");
		for (int i = 0, il = varDeclElements.getLength(); i < il; i++) {
			Element variableElement = (Element) varDeclElements.item(i);
			String variableName = variableElement.getElementsByTagName("VarName").item(0).getTextContent();
			String typeDeclaration = "";

			Node typeDeclarationElement = variableElement.getElementsByTagName("TypeDeclaration").item(0);
			if (typeDeclarationElement != null) {
				typeDeclaration = typeDeclarationElement.getTextContent();
			}

			NodeList enclosedExprElements = variableElement.getElementsByTagName("EnclosedExpr");

			for (int j = 0, jl = enclosedExprElements.getLength(); j < jl; j++) {
				_processEnclosedExpressions(parsingResult, (Element) enclosedExprElements.item(j));
			}

			if (typeDeclaration.equals("as element()")) {
				parsingResult.variables.put("$" + variableName,
						variableElement.getElementsByTagName("VarValue").item(0).getTextContent());
			}

			if (typeDeclaration.endsWith("string")) {
				parsingResult.variables.put("$" + variableName,
						variableElement.getElementsByTagName("VarValue").item(0).getTextContent());
			}

			prolog.append(delim).append(((Element) varDeclElements.item(i)).getTextContent()).append("; ");
			delim = "declare ";
		}

		parsingResult.prolog = prolog.toString();

		// load and the tree template
		baseTreeGeneratorTemplate = parsingResult.prolog
				+ new Scanner(new FileInputStream(new File(addonDirectory + File.separator + "target"
						+ File.separator + "tree-template.xq")), "UTF-8").useDelimiter("\\A").next();
		System.out.println("variableElements: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
				/ 1000.0);

		System.setProperty("javax.xml.transform.TransformerFactory",
				"com.sun.org.apache.xalan.internal.xsltc.trax.TransformerFactoryImpl");

		// FileUtils.writeStringToFile(new File("/home/claudius/a.xml"),
		// XML.xmlToString(xqueryParserOutputDocumentElement, "no", "yes"));

		// process function calls
		long functionCallElementsStart = (long) (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) / 1000.0);
		System.out.println("functionCallElementsStart: " + functionCallElementsStart);
		NodeList functionCallElements = xqueryParserOutputDocumentElement
				.getElementsByTagName("FunctionCall");
		for (int i = 0, il = functionCallElements.getLength(); i < il; i++) {
			Element functionCallElement = (Element) functionCallElements.item(i);

			if (functionCallElement == null) {
				continue;
			}

			String functionName = functionCallElement.getFirstChild().getFirstChild().getTextContent();

			if (functionName.equals("ua:action")) {
				ua__action(functionCallElement);
			}

			if (functionName.equals("ua:observer")) {
				ua__observer(functionCallElement, parsingResult);
			}

			if (functionName.equals("ua:connect-observer")) {
				ua__connect_observer(functionCallElement, parsingResult);
			}

			if (functionName.equals("oxy:uuid")) {
				functionCallElement.setTextContent("'id-${uuid}'");
			}

			if (functionName.equals("ua-dt:xpath-selector")) {
				String textContent = functionCallElement.getTextContent();
				functionCallElement.setTextContent(_processNodeSelector(textContent));
			}

			if (functionName.equals("ua:add-event-listener")) {
				NodeList argumentElements = functionCallElement.getElementsByTagName("Argument");

				String eventTarget = _processStringLiteral(argumentElements.item(0).getTextContent());
				String eventType = _processStringLiteral(argumentElements.item(1).getTextContent());
				String listener = argumentElements.item(2).getTextContent();

				if (eventType.equals("load")) {
					if (listener.contains("oxy:execute-action-by-name")) {
						listener = _processStringLiteral(listener.replaceAll(
								"oxy:execute-action-by-name\\(", "").replaceAll("\\)", ""));
						parsingResult.actionsByName.add(listener);
					}
					if (listener.contains("oxy:execute-action-by-class")) {
						listener = _processStringLiteral(listener.replaceAll(
								"oxy:execute-action-by-class\\(", "").replaceAll("\\)", ""));
						parsingResult.actionsByClass.get("load").add(listener);
					}
				}
			}

			if (functionName.equals("ua:template")) {
				ua__template(functionCallElement, parsingResult);
			}

			if (functionName.equals("ua:attach-template")) {
				ua__attach_template(functionCallElement, parsingResult);
			}

		}

		System.out.println("functionCallElements: "
				+ TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) / 1000.0);

		for (Map.Entry<String, Element> derivedActionElement : derivedActionElements.entrySet()) {
			actionsWriter.writeStartElement("action");
			String actionId = derivedActionElement.getKey();
			_writeFieldElement("id", actionId);
			for (String actionParameter : actionParameters) {
				_writeFieldElement(actionParameter, "");
			}
			_processCodeBlockArgument(derivedActionElement.getValue(), actionId);
			actionsWriter.writeEndElement();
		}

		for (SimpleAction simpleAction : simpleActions) {
			actionsWriter.writeStartElement("action");
			_writeFieldElement("id", simpleAction.getId());
			_writeFieldElement("name", simpleAction.getName());
			for (String actionParameter : Arrays.copyOfRange(actionParameters, 1, 5)) {
				_writeFieldElement(actionParameter, "");
			}
			actionsWriter.writeStartElement("field");
			actionsWriter.writeAttribute("name", "actionModes");
			actionsWriter.writeStartElement("actionMode-array");

			actionsWriter.writeStartElement("actionMode");
			_writeFieldElement("xpathCondition", "");
			actionsWriter.writeStartElement("field");
			actionsWriter.writeAttribute("name", "argValues");
			actionsWriter.writeStartElement("map");

			_writeEntryElement("dialogId", simpleAction.getTemplateId());

			actionsWriter.writeEndElement();
			actionsWriter.writeEndElement();
			_writeFieldElement("operationID", "ro.kuberam.oxygen.addonBuilder.operations.ShowDialog");
			actionsWriter.writeEndElement();

			actionsWriter.writeEndElement();
			actionsWriter.writeEndElement();

			actionsWriter.writeEndElement();
		}

		actionsWriter.writeEndElement();
		actionsWriter.flush();

		Document actionsOutputDoc = db.parse(new ByteArrayInputStream(actionsOutput.toByteArray()));

		// write actions to the *.framework file
		Document frameworkDoc = db.parse(frameworkDescriptor);
		Node actionArrayElement = frameworkDoc.getDocumentElement().getElementsByTagName("action-array")
				.item(0);
		Node actionArrayElementParent = actionArrayElement.getParentNode();
		actionArrayElementParent.removeChild(actionArrayElement);

		actionArrayElementParent.appendChild(frameworkDoc.importNode(actionsOutputDoc.getDocumentElement(),
				true));

		TransformerFactory transformerFactory = TransformerFactory.newInstance();
		Transformer transformer = transformerFactory.newTransformer();
		DOMSource source = new DOMSource(frameworkDoc);
		StreamResult streamResult = new StreamResult(frameworkDescriptor);
		transformer.transform(source, streamResult);

		// write the files with observers, actions by class, etc.
		parsingResult.writeToFile(targetDirectory, addonDirectory);

		System.out
				.println("duration: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) / 1000.0);

	}

	private void ua__attach_template(Element functionCallElement, ParsingResult parsingResult) {
		NodeList argumentElements = functionCallElement.getElementsByTagName("Argument");

		String nodeSelector = _processNodeSelector(argumentElements.item(0).getTextContent());
		String templateId = _processStringLiteral(argumentElements.item(2).getTextContent());
		String template = parsingResult.templates.get(templateId);

		if (template != null && !template.contains("<dialog")) {
			parsingResult.attachedTemplates += nodeSelector + "{content: " + template + ";}\r\n";
		}
	}

	private void ua__template(Element functionCallElement, ParsingResult parsingResult) {
		NodeList argumentElements = functionCallElement.getElementsByTagName("Argument");

		String templateId = _processStringLiteral(argumentElements.item(0).getTextContent());
		if (parsingResult.templates.containsKey(templateId)) {
			throw new RuntimeException(ErrorMessages.err_UA01.replace("$id", "'" + templateId + "'"));
		}

		Element templateContent = (Element) argumentElements.item(1);
		NodeList enclosedExprElements = templateContent.getElementsByTagName("EnclosedExpr");

		for (int i = 0, il = enclosedExprElements.getLength(); i < il; i++) {
			_processEnclosedExpressions(parsingResult, (Element) enclosedExprElements.item(i));
		}

		String templateContentAsString = templateContent.getTextContent();

		NodeList templateContentAsXml = XML.parse(templateContentAsString).getChildNodes();
		String processedTemplateContent = "";

		for (int i = 0, il = templateContentAsXml.getLength(); i < il; i++) {
			processedTemplateContent += _processHTMLTemplateContent(templateContentAsXml.item(i),
					parsingResult, templateId);
		}

		processedTemplateContent = processedTemplateContent.trim();

		if (processedTemplateContent.length() != 0) {
			parsingResult.templates.put(templateId, processedTemplateContent);
		}
	}

	private void _processEnclosedExpressions(ParsingResult parsingResult, Element enclosedExprElement) {
		NodeList varRefElements = enclosedExprElement.getElementsByTagName("VarRef");
		int varRefElementsNumber = varRefElements.getLength();

		String enclosedExpressionTextContent = enclosedExprElement.getTextContent().trim();

		if (varRefElementsNumber > 0) {
			for (int i = 0, il = varRefElementsNumber; i < il; i++) {
				Node varRefElement = varRefElements.item(i);
				String variableName = varRefElement.getTextContent();
				String variableValue = parsingResult.variables.get(variableName);
				enclosedExpressionTextContent = enclosedExpressionTextContent
						.replace(variableName, variableValue).replaceAll("^\\{\\s*", "")
						.replaceAll("\\s*\\}$", "");
			}
		}

		enclosedExpressionTextContent = _processXpathExpression(enclosedExpressionTextContent,
				parsingResult);

		if (enclosedExpressionTextContent.startsWith("oxy_xpath_start<")) {
			enclosedExpressionTextContent = enclosedExpressionTextContent.replace("oxy_xpath_start", "")
					.replace("oxy_xpath_end", "");

		}

		enclosedExprElement.setTextContent("");

		enclosedExprElement.setTextContent(enclosedExpressionTextContent);
	}

	private String _processXpathExpression(String xpathExpression, ParsingResult parsingResult) {
		String result = "";

		xpathExpression = xpathExpression.replaceAll("^\\s*\\{\\s*", "").replaceAll("\\s*\\}\\s*$", "");

		result = oxyXpathExpressionStartMarker + xpathExpression + oxyXpathExpressionEndMarker;

		if (xpathExpression.contains("ua:get-template(")) {
			Matcher extractTemplateIdPatternMatcher = extractTemplateIdPattern.matcher(xpathExpression);

			while (extractTemplateIdPatternMatcher.find()) {
				String templateId = extractTemplateIdPatternMatcher.group(3);
				String templateFunctionCall = extractTemplateIdPatternMatcher.group();
				xpathExpression = xpathExpression.replace(templateFunctionCall,
						parsingResult.templates.get(templateId) + " ");
			}

			result = "<template>" + xpathExpression + "</template>";
		}

		return result;
	}

	private String _processHTMLTemplateContent(Node node, ParsingResult parsingResult, String templateId) {
		String result = "";

		switch (node.getNodeType()) {
		case 1:
			String nodeName = node.getNodeName();

			if (nodeName.equals("button")) {
				result = buttonElementTemplate(node);
			}
			if (nodeName.equals("input")) {
				result = inputHTMLElementTemplate(node, parsingResult);
			}
			if (nodeName.equals("select")) {
				result = selectHTMLElementTemplate(node, parsingResult);
			}
			if (nodeName.equals("template")) {
				String textContent = node.getTextContent().trim();
				result = _processOxyGetTemplate(textContent);
			}
			if (nodeName.equals("textarea")) {
				result = textAreaHTMLElementTemplate(node, parsingResult);
			}
			if (nodeName.equals("dialog")) {
				result = dialogHTMLElementTemplate(templateId, node, parsingResult);
			}
			if (nodeName.equals("datalist")) {
				result = datalistHTMLElementTemplate(node, parsingResult);
			}
			if (nodeName.equals("tree")) {
				result = treeElementTemplate(node);
			}
			break;
		case 3:
			String textContent = node.getTextContent().trim();

			if (!textContent.equals("")) {
				if (textContent.contains(oxyXpathExpressionStartMarker)) {
					result = _processOxyXpathExpression(textContent);
				} else {
					result = "\"" + textContent.replaceAll("&nbsp;", " ") + "\"";
				}

				result = _processOxyGetTemplate(result);
			}
			break;
		}

		return result + " ";
	}

	private String _processOxyGetTemplate(String textContent) {
		String result = "";
		if (textContent.contains("oxy:get-template(")) {
			OxyEditorDescriptor oxyEditorDescriptor = new OxyEditorDescriptor();
			textContent = textContent.replace("ua:get-template(oxy:get-template(", "").replaceAll(
					"\\)\\)$", "");
			String templateName = textContent.substring(1, textContent.indexOf("\","));
			String optionsMap = textContent.substring(textContent.indexOf("\",") + 2).trim();

			optionsMap = optionsMap.substring(optionsMap.indexOf("{") + 1);
			optionsMap = optionsMap.substring(0, optionsMap.indexOf("}")).trim();

			String[] options = optionsMap.split(",\\s+\"");

			if (builtinFormControlNames.contains(templateName)) {
				oxyEditorDescriptor.setType(_processStringLiteral(templateName));
			} else {
				templateName = _processStringLiteral(templateName);
				oxyEditorDescriptor.setRendererClassName(templateName);
				oxyEditorDescriptor.setSwingEditorClassName(templateName);
			}

			for (String option : options) {
				String optionName = option.substring(0, option.indexOf(":=")).replace("\"", "").trim();
				String optionValue = option.substring(option.indexOf(":=") + 2).trim();
				if (optionValue.startsWith("\"")) {
					optionValue = optionValue.endsWith("\"") ? optionValue : optionValue + "\"";
				} else {
					optionValue = "oxy_xpath(\"" + optionValue + "\")";
				}
				oxyEditorDescriptor.setCustomProperty(optionName, _processStringLiteral(optionValue));

			}

			result = oxyEditorDescriptor.toString();
		} else {
			result = textContent;
		}

		return result;
	}

	private String dialogHTMLElementTemplate(String templateId, Node node, ParsingResult parsingResult) {
		String result = "";

		Element element = (Element) node;
		Style style = new Style(element.getAttribute("style"));

		String title = element.getAttribute("title");
		String open = element.getAttribute("open");
		open = (open != "") ? open : "false";
		String type = element.getAttribute("data-ua-type");
		type = (type != "") ? type : "modal";
		String dataSrc = element.getAttribute("data-src");

		result = XML.xmlToString(node, "NO", "YES");

		parsingResult.dialogs.put(templateId, new DialogModel(templateId, type, title, style.width,
				style.height, style.resize, style.margin, dataSrc, "OxygenAddonBuilder", result));

		return result;
	}

	private String datalistHTMLElementTemplate(Node node, ParsingResult parsingResult2) {
		Element element = (Element) node;
		NodeList nodeChildNodes = node.getChildNodes();
		String id = element.getAttribute("id");
		StringBuilder values = new StringBuilder();
		String delim = "";

		for (int i = 0, il = nodeChildNodes.getLength(); i < il; i++) {
			Node childNode = nodeChildNodes.item(i);
			String childNodeName = childNode.getNodeName();

			if (childNodeName.equals("option")) {
				values.append(delim).append(childNode.getAttributes().getNamedItem("label").getNodeValue());
				delim = ",";
			}
		}

		parsingResult.templates.put(id, values.toString());
		parsingResult.datalists.put(id, values.toString());

		return "";
	}

	private String treeElementTemplate(Node node) {
		OxyEditorDescriptor oxyEditorDescriptor = new OxyEditorDescriptor();
		oxyEditorDescriptor.setType("");
		oxyEditorDescriptor
				.setRendererClassName("ro.kuberam.oxygen.addonBuilder.templates.java.tree.TreeFormControl");
		oxyEditorDescriptor
				.setSwingEditorClassName("ro.kuberam.oxygen.addonBuilder.templates.java.tree.TreeFormControl");
		Node treeitemNode = ((Element) node).getElementsByTagName("treeitem").item(0);
		Node itemtemplateNode = ((Element) node).getElementsByTagName("itemtemplate").item(0);

		NamedNodeMap nodeAttrs = node.getAttributes();
		Node styleAttrNode = nodeAttrs.getNamedItem("style");
		Style style;
		if (styleAttrNode == null) {
			style = new Style("tree");
		} else {
			style = new Style("tree", styleAttrNode.getNodeValue());
		}

		for (int i = 0, il = nodeAttrs.getLength(); i < il; i++) {
			Node attr = nodeAttrs.item(i);
			String attrName = attr.getNodeName();
			String attrValue = attr.getNodeValue();

			if (attrName.equals("data-ua-ref")) {
				_processReferenceAttribute(oxyEditorDescriptor, attrValue, parsingResult);
			}

			_processDataAttribute(attrName, attrValue, oxyEditorDescriptor);
		}

		String height = Integer.toString(style.height);
		oxyEditorDescriptor.setColumns(Integer.toString(style.width));
		oxyEditorDescriptor.setRows(height);

		String treeitem = _removeOxyXpathExpressionMarkers(treeitemNode.getTextContent().trim());
		treeitem = treeitem.replace("ua:context()", "$node");

		String itemtemplateAsString = XML.xmlToString(itemtemplateNode, "yes", "yes");
		itemtemplateAsString = XML.xmlToString(itemtemplateNode, "no", "yes")
				.substring(0, itemtemplateAsString.length() - 16).substring(14).trim();
		itemtemplateAsString = itemtemplateAsString.replaceAll(">\\s+<", "><");

		String treeGeneratorTemplateId = UUID.randomUUID().toString().replaceAll("-", "");
		oxyEditorDescriptor.setCustomProperty("treeGeneratorTemplateId", treeGeneratorTemplateId);

		String treeGeneratorTemplate = baseTreeGeneratorTemplate
				.replace("${root-nodes}", oxyEditorDescriptor.getEditValue())
				.replace("${root-nodes-path}", "\"" + oxyEditorDescriptor.getEditValue() + "\"")
				.replace("${treeitem}", treeitem).replace("${item-template}", itemtemplateAsString)
				.replace("${tree-height}", height + "px");

		parsingResult.templates.put(treeGeneratorTemplateId, treeGeneratorTemplate);

		return oxyEditorDescriptor.toString();
	}

	private String _processOxyXpathExpression(String textContent) {
		textContent = "\""
				+ textContent.replaceAll(oxyXpathExpressionStartMarker, "\" oxy_xpath(\"").replaceAll(
						oxyXpathExpressionEndMarker, "\") \"") + "\"";
		textContent = textContent.replaceAll("\"\"", "").replaceAll(" \" \" ", " ").trim();
		return textContent;
	}

	private String textAreaHTMLElementTemplate(Node node, ParsingResult parsingResult) {
		OxyEditorDescriptor oxyEditorDescriptor = new OxyEditorDescriptor();
		oxyEditorDescriptor.setType("textArea");
		NamedNodeMap nodeAttrs = node.getAttributes();

		for (int i = 0, il = nodeAttrs.getLength(); i < il; i++) {
			Node attr = nodeAttrs.item(i);
			String attrName = attr.getNodeName();
			String attrValue = attr.getNodeValue();

			if (attrName.equals("data-ua-ref")) {
				_processReferenceAttribute(oxyEditorDescriptor, attrValue, parsingResult);
			}

			if (attrName.equals("cols")) {
				oxyEditorDescriptor.setColumns(attrValue);
			}

			if (attrName.equals("rows")) {
				oxyEditorDescriptor.setRows(attrValue);
			}

			_processDataAttribute(attrName, attrValue, oxyEditorDescriptor);
		}

		return oxyEditorDescriptor.toString();
	}

	private void _processDataAttribute(String attrName, String attrValue,
			OxyEditorDescriptor oxyEditorDescriptor) {
		if (attrName.startsWith("data-") && !attrName.equals("data-ua-ref")) {
			attrValue = (attrValue instanceof String) ? "\"" + attrValue + "\"" : attrValue;
			attrValue = (attrValue.equals("\"false\"")) ? "false" : attrValue;
			oxyEditorDescriptor.setCustomProperty(attrName.substring(5), attrValue);
		}
	}

	private String selectHTMLElementTemplate(Node node, ParsingResult parsingResult) {

		OxyEditorDescriptor oxyEditorDescriptor = new OxyEditorDescriptor();
		NamedNodeMap nodeAttrs = node.getAttributes();

		Node multipleAttrNode = nodeAttrs.getNamedItem("multiple");
		Node refAttrNode = nodeAttrs.getNamedItem("data-ua-ref");
		Node contenteditableAttrNode = nodeAttrs.getNamedItem("contenteditable");
		Node sizeAttrNode = nodeAttrs.getNamedItem("size");
		Node appearanceAttrNode = nodeAttrs.getNamedItem("data-ua-appearance");
		Node styleAttrNode = nodeAttrs.getNamedItem("style");

		Style style;
		if (styleAttrNode == null) {
			style = new Style("select");
		} else {
			style = new Style("select", styleAttrNode.getNodeValue());
		}

		String ref = (refAttrNode == null) ? "" : refAttrNode.getNodeValue();
		String contenteditable = (contenteditableAttrNode == null) ? "" : contenteditableAttrNode
				.getNodeValue();
		String size = (sizeAttrNode == null) ? "" : sizeAttrNode.getNodeValue();
		size = ((multipleAttrNode == null) && size.equals("")) ? "4" : size;
		String appearance = (appearanceAttrNode == null) ? "" : appearanceAttrNode.getNodeValue();

		NodeList nodeChildNodes = node.getChildNodes();

		StringBuilder values = new StringBuilder();
		StringBuilder labels = new StringBuilder();
		String delim = "";

		for (int i = 0, il = nodeChildNodes.getLength(); i < il; i++) {
			Node childNode = nodeChildNodes.item(i);
			String childNodeName = childNode.getNodeName();

			if (childNodeName.equals("option")) {
				values.append(delim).append(childNode.getAttributes().getNamedItem("value").getNodeValue());
				labels.append(delim).append(childNode.getAttributes().getNamedItem("label").getNodeValue());
				delim = ",";
			}

		}

		oxyEditorDescriptor.setValues(values.toString());
		oxyEditorDescriptor.setLabels(labels.toString());
		oxyEditorDescriptor.setColumns(Integer.toString(style.width));

		if (multipleAttrNode == null) {
			oxyEditorDescriptor.setType("combo");

			_processReferenceAttribute(oxyEditorDescriptor, ref, parsingResult);
			oxyEditorDescriptor.setEditable(contenteditable);

		} else {

			switch (appearance) {
			case "oxy:popupWithMultipleSelection":
				oxyEditorDescriptor.setType("popupSelection");

				_processReferenceAttribute(oxyEditorDescriptor, ref, parsingResult);

				oxyEditorDescriptor.setSelectionMode("multiple");

				break;
			default:
				// result +=
				// "oxy_editor(rendererClassName, \"ro.kuberam.oxygen.addonBuilder.templates.java.select.SelectFormControl\", swingEditorClassName, \"ro.kuberam.oxygen.addonBuilder.templates.java.select.SelectFormControl\", ";
				//
				// result = _processReferenceAttribute(result, ref,
				// parsingResult);
				//
				// for (int i = 0, il = nodeChildNodes.getLength(); i < il; i++)
				// {
				// Node childNode = nodeChildNodes.item(i);
				// String childNodeName = childNode.getNodeName();
				//
				// if (childNodeName.equals("option")) {
				// values +=
				// childNode.getAttributes().getNamedItem("value").getNodeValue()
				// + " ";
				// }
				//
				// }
				//
				// result += "editable, " + contenteditable + ", size, \"" +
				// size + "\", valuesAsString, \""
				// + values.trim() + "\")";

			}
		}

		return oxyEditorDescriptor.toString();
	}

	private String inputHTMLElementTemplate(Node node, ParsingResult parsingResult) {
		OxyEditorDescriptor oxyEditorDescriptor = new OxyEditorDescriptor();
		oxyEditorDescriptor.setType("text");
		NamedNodeMap nodeAttrs = node.getAttributes();

		for (int i = 0, il = nodeAttrs.getLength(); i < il; i++) {
			Node attr = nodeAttrs.item(i);
			String attrName = attr.getNodeName();
			String attrValue = attr.getNodeValue();

			if (attrName.equals("data-ua-ref")) {
				_processReferenceAttribute(oxyEditorDescriptor, attrValue, parsingResult);
			}

			if (attrName.equals("size")) {
				oxyEditorDescriptor.setColumns(attrValue);
			}

			if (attrName.equals("list")) {
				oxyEditorDescriptor.setValues("@" + attrValue);
			}
		}

		return oxyEditorDescriptor.toString();
	}

	private String buttonElementTemplate(Node node) {
		OxyEditorDescriptor oxyEditorDescriptor = new OxyEditorDescriptor();
		oxyEditorDescriptor.setType("button");
		NamedNodeMap nodeAttrs = node.getAttributes();

		for (int i = 0, il = nodeAttrs.getLength(); i < il; i++) {
			Node attr = nodeAttrs.item(i);
			String attrName = attr.getNodeName();
			String attrValue = attr.getNodeValue();
			String actionID = "";
			String action = "";

			if (attrName.equals("onclick")) {
				attrValue = _removeOxyXpathExpressionMarkers(attrValue).replaceAll("'\\)", "");

				if (attrValue.contains("oxy:execute-action-by-name")) {
					actionID = attrValue.replaceAll("oxy:execute-action-by-name\\('", "");
					oxyEditorDescriptor.setActionID(actionID);
				}

				if (attrValue.contains("ua:show-template")) {
					actionID = "simpleAction" + UUID.randomUUID().toString().replaceAll("-", "");
					String templateId = attrValue.replaceAll("ua:show-template\\('", "");

					SimpleAction simpleAction = new SimpleAction(actionID, node.getTextContent(),
							templateId);
					simpleActions.add(simpleAction);

					oxyEditorDescriptor.setActionID(actionID);
				}

				if (attrValue.contains("oxy:xquery")) {
					action = "@" + attrValue.replaceAll("oxy:xquery\\('", "");
					oxyEditorDescriptor.setAction(action);
				}

			}

			if (attrName.equals("style")) {
				String[] styleProperties = attrValue.split(";");

				for (String styleProperty : styleProperties) {
					styleProperty = styleProperty.trim();

					if (styleProperty.startsWith("background-color: transparent")) {
						oxyEditorDescriptor.setTransparent("true");
					}
					if (styleProperty.startsWith("visibility:")) {
						String visibilityValue = styleProperty.substring(11).trim();
						if (visibilityValue.contains(oxyXpathExpressionStartMarker)) {
							visibilityValue = _processOxyXpathExpression(visibilityValue);
						}
						oxyEditorDescriptor.setVisible(visibilityValue);
					}
					if (styleProperty.startsWith("color:")) {
						String colorValue = styleProperty.substring(styleProperty.indexOf(":") + 1).trim();
						oxyEditorDescriptor.setColor(colorValue);
					}
				}
			}

			if (attrName.equals("disabled")) {
				if (attrValue.contains(oxyXpathExpressionStartMarker)) {
					attrValue = _processOxyXpathExpression(attrValue);
				}
				oxyEditorDescriptor.setDisabled(attrValue);
			}

			if (actionsWithCaretContext.contains(actionID)) {
				oxyEditorDescriptor.setActionContext("caret");
			}

			_processDataAttribute(attrName, attrValue, oxyEditorDescriptor);
		}
		// System.out.println(oxyEditorDescriptor.toString());

		return oxyEditorDescriptor.toString();
	}

	private String _removeOxyXpathExpressionMarkers(String oxyXpathExpression) {
		return oxyXpathExpression.replaceAll(oxyXpathExpressionStartMarker, "").replaceAll(
				oxyXpathExpressionEndMarker, "");
	}

	private void _processReferenceAttribute(OxyEditorDescriptor oxyEditorDescriptor, String attrValue,
			ParsingResult parsingResult) {
		attrValue = _removeOxyXpathExpressionMarkers(_processXpathExpression(attrValue, parsingResult));
		if (attrValue.equals("text()")) {
			oxyEditorDescriptor.setEdit("#text");
		} else {
			oxyEditorDescriptor.setEdit(attrValue);
		}
	}

	private void ua__action(Element functionCallElement) throws XMLStreamException {
		NodeList argumentElements = functionCallElement.getElementsByTagName("Argument");

		Node functionIdArgument = argumentElements.item(0);
		Element functionParametersArgument = (Element) argumentElements.item(1);
		Element codeBlockArgument = (Element) argumentElements.item(2);

		String script = codeBlockArgument.getTextContent();

		if (script.startsWith("oxy:execute-xquery-script")) {
			// create action description in CSS-like style
			OxyAction oxyAction = new OxyAction();

			oxyAction.setId(_processStringLiteral(functionIdArgument.getTextContent()));
			createActionDescription(oxyAction, functionParametersArgument);
			oxyAction.setOperation("ro.sync.ecss.extensions.commons.operations.XQueryOperation");

			script = script.replace("oxy:execute-xquery-script(\"", "");
			script = script.substring(0, script.lastIndexOf("\")"));
			oxyAction.setArgument("script", script);
			oxyAction.setArgument("action", "After");

			parsingResult.actions.add(oxyAction.toLessDeclaration());
		} else {

			actionsWriter.writeStartElement("action");
			_writeAction(_processStringLiteral(functionIdArgument.getTextContent()),
					functionParametersArgument, codeBlockArgument);
			actionsWriter.writeEndElement();
		}

	}

	private void createActionDescription(OxyAction oxyAction, Element functionParametersArgument) {
		NodeList mapKeyExprElements = functionParametersArgument.getElementsByTagName("MapKeyExpr");
		NodeList mapValueExprElements = functionParametersArgument.getElementsByTagName("MapValueExpr");

		for (int i = 0, il = mapKeyExprElements.getLength(); i < il; i++) {
			String argumentName = _processStringLiteral(mapKeyExprElements.item(i).getTextContent());
			String argumentValue = _processStringLiteral(mapValueExprElements.item(i).getTextContent());

			switch (argumentName) {
			case "name":
				oxyAction.setName(argumentValue);
			case "description":
				oxyAction.setDescription(argumentValue);
			}

		}
	}

	private void _writeAction(String actionId, Element functionParametersArgument, Element codeBlockArgument)
			throws XMLStreamException {
		_writeFieldElement("id", actionId);
		_processFunctionParameters(functionParametersArgument);
		_processCodeBlockArgument(codeBlockArgument, actionId);
	}

	private void ua__observer(Element functionCallElement, ParsingResult parsingResult)
			throws XMLStreamException {
		NodeList argumentElements = functionCallElement.getElementsByTagName("Argument");
		String id = _processStringLiteral(argumentElements.item(0).getTextContent());
		String[] actionHandlers = _processStringLiteral(argumentElements.item(1).getTextContent()).split(
				" ");
		parsingResult.observers.put(id, actionHandlers);
	}

	private void ua__connect_observer(Element functionCallElement, ParsingResult parsingResult) {
		NodeList argumentElements = functionCallElement.getElementsByTagName("Argument");

		String observerHandler = _processStringLiteral(argumentElements.item(0).getTextContent());
		String nodeSelector = _processNodeSelector(argumentElements.item(1).getTextContent());
		Element optionsArgument = (Element) argumentElements.item(3);
		NodeList mapKeyExprElements = optionsArgument.getElementsByTagName("MapKeyExpr");
		NodeList mapValueExprElements = optionsArgument.getElementsByTagName("MapValueExpr");
		Map<String, String> unprocessedOptions = new HashMap<String, String>();

		for (int i = 0, il = mapKeyExprElements.getLength(); i < il; i++) {
			unprocessedOptions.put(_processStringLiteral(mapKeyExprElements.item(i).getTextContent()),
					_processStringLiteral(mapValueExprElements.item(i).getTextContent()));
		}

		Map<String, Object> options = new HashMap<String, Object>();
		if (unprocessedOptions.containsKey("attributes")) {
			options.put("attributes", unprocessedOptions.get("attributes"));
		}
		if (unprocessedOptions.containsKey("attributeFilter")) {
			String value = unprocessedOptions.get("attributeFilter");
			value = value.replaceAll("\\[", "").replaceAll("\\]", "").replaceAll("'", "");
			List<String> valueAsList = Arrays.asList(value.split("\\s*,\\s*"));
			options.put("attributeFilter", valueAsList);
		}

		parsingResult.connectObserverActions.put(nodeSelector, new ObserverConnection(observerHandler,
				nodeSelector, options));
		parsingResult.nodeSelectors.put(nodeSelector, "xpath");
	}

	private void _processCodeBlockArgument(Element argumentElement, String actionId)
			throws XMLStreamException {
		NodeList ifExprElements = argumentElement.getElementsByTagName("IfExpr");
		int ifExprElementsNumber = ifExprElements.getLength();

		actionsWriter.writeStartElement("field");
		actionsWriter.writeAttribute("name", "actionModes");
		actionsWriter.writeStartElement("actionMode-array");

		for (int i = 0, il = ifExprElementsNumber; i < il; i++) {
			Element ifExprElement = (Element) ifExprElements.item(i);

			_writeBasicActionMode(ifExprElement, ifExprElement.getElementsByTagName("Expr").item(0)
					.getTextContent(), actionId);
		}

		if (ifExprElementsNumber == 0) {
			_writeBasicActionMode((Element) argumentElement, "", actionId);
		}

		actionsWriter.writeEndElement();
		actionsWriter.writeEndElement();

	}

	private void _writeBasicActionMode(Element argumentElement, String xpathCondition, String actionId)
			throws XMLStreamException {
		String authorOperationName = "";
		NodeList updatingExprElements = argumentElement.getElementsByTagName("UpdatingExpr");
		int updatingExprElementsNumber = updatingExprElements.getLength();

		actionsWriter.writeStartElement("actionMode");
		_writeFieldElement("xpathCondition", xpathCondition);
		actionsWriter.writeStartElement("field");
		actionsWriter.writeAttribute("name", "argValues");
		actionsWriter.writeStartElement("map");

		// updating expressions
		if (updatingExprElementsNumber > 1) {
			String simpleActionIds = "";

			for (int i = 0; i < updatingExprElementsNumber; i++) {
				String simpleActionId = "derivedAction" + UUID.randomUUID().toString().replaceAll("-", "");
				derivedActionElements.put(simpleActionId, (Element) updatingExprElements.item(i)
						.getParentNode());
				simpleActionIds += simpleActionId + "\r";
			}

			_writeEntryElement("actionIDs", simpleActionIds);
			authorOperationName = "ro.sync.ecss.extensions.commons.operations.ExecuteMultipleActionsOperation";
		} else if (updatingExprElementsNumber == 1) {
			authorOperationName = _writeActionMode((Element) updatingExprElements.item(0).getFirstChild());
			// other functions
		} else {
			authorOperationName = argumentElement.getTextContent();
			if (authorOperationName.contains("oxy:execute-action-by-class")) {
				authorOperationName = _processStringLiteral(authorOperationName.replaceAll(
						"oxy:execute-action-by-class\\(", "").replaceAll("\\)", ""));
			} else if (authorOperationName.contains("ua:range-surround-contents")) {
				NodeList argumentElements = argumentElement.getElementsByTagName("Argument");
				_writeEntryElement("fragment", argumentElements.item(2).getTextContent());
				authorOperationName = "ro.sync.ecss.extensions.commons.operations.SurroundWithFragmentOperation";
				actionsWithCaretContext.add(actionId);
			} else if (authorOperationName.contains("oxy:execute-xquery-script")) {
				NodeList argumentElements = argumentElement.getElementsByTagName("Argument");
				_writeEntryElement("script", _processStringLiteral(argumentElements.item(0)
						.getTextContent()));
				_writeEntryElement("action", "After");
				authorOperationName = "ro.sync.ecss.extensions.commons.operations.XQueryOperation";
				actionsWithCaretContext.add(actionId);
			} else {
				authorOperationName = "ro.kuberam.oxygen.addonBuilder.operations.VoidOperation";
			}
		}

		actionsWriter.writeEndElement();
		actionsWriter.writeEndElement();
		_writeFieldElement("operationID", authorOperationName);
		actionsWriter.writeEndElement();

		// oxy_action(
		// name, "Search",
		// operation,
		// "ro.sync.ecss.extensions.commons.operations.XQueryOperation",
		// arg-script,
		// "import module namespace biblio = 'http://dlri.ro/ns/biblio/' at 'form-controls/search.xq'; biblio:run()",
		// arg-action, "After"
		// )
	}

	private String _writeActionMode(Element updatingExprElement) throws XMLStreamException {
		String updatingExprType = updatingExprElement.getNodeName();
		String authorOperationName = "";

		if (updatingExprType.equals("RenameExpr")) {
			authorOperationName = _processRenameExpr(updatingExprElement);
		}
		if (updatingExprType.equals("InsertExpr")) {
			authorOperationName = _processInsertExpr(updatingExprElement);
		}
		if (updatingExprType.equals("DeleteExpr")) {
			authorOperationName = _processDeleteExpr(updatingExprElement);
		}
		if (updatingExprType.equals("ReplaceExpr")) {
			authorOperationName = _processReplaceExpr(updatingExprElement);
		}

		return authorOperationName;
	}

	private void _processFunctionParameters(Element functionParametersArgument) throws DOMException,
			XMLStreamException {
		Map<String, String> actionArgumentsMap = new HashMap<String, String>();
		actionArgumentsMap.put("name", "");
		actionArgumentsMap.put("description", "");
		actionArgumentsMap.put("largeIconPath", "");
		actionArgumentsMap.put("smallIconPath", "");
		actionArgumentsMap.put("accessKey", "");
		actionArgumentsMap.put("accelerator", "");

		NodeList mapKeyExprElements = functionParametersArgument.getElementsByTagName("MapKeyExpr");
		NodeList mapValueExprElements = functionParametersArgument.getElementsByTagName("MapValueExpr");

		for (int i = 0, il = mapKeyExprElements.getLength(); i < il; i++) {
			actionArgumentsMap.put(_processStringLiteral(mapKeyExprElements.item(i).getTextContent()),
					_processStringLiteral(mapValueExprElements.item(i).getTextContent()));
		}
		for (Map.Entry<String, String> actionArgument : actionArgumentsMap.entrySet()) {
			_writeFieldElement(actionArgument.getKey(), actionArgument.getValue());
		}
	}

	private void _writeFieldElement(String type, String textContent) throws XMLStreamException {
		actionsWriter.writeStartElement("field");
		actionsWriter.writeAttribute("name", type);
		actionsWriter.writeStartElement("String");
		actionsWriter.writeCharacters(textContent);
		actionsWriter.writeEndElement();
		actionsWriter.writeEndElement();
	}

	private void _writeEntryElement(String content1, String content2) throws XMLStreamException {
		actionsWriter.writeStartElement("entry");
		actionsWriter.writeStartElement("String");
		actionsWriter.writeCharacters(content1);
		actionsWriter.writeEndElement();
		actionsWriter.writeStartElement("String");
		actionsWriter.writeCharacters(content2);
		actionsWriter.writeEndElement();
		actionsWriter.writeEndElement();
	}

	private String _processRenameExpr(Element updatingExprElement) throws XMLStreamException {
		_writeEntryElement("elementName",
				_processStringLiteral(updatingExprElement.getElementsByTagName("NewNameExpr").item(0)
						.getTextContent()));
		_writeEntryElement("elementLocation", updatingExprElement.getElementsByTagName("TargetExpr")
				.item(0).getTextContent());

		return "ro.sync.ecss.extensions.commons.operations.RenameElementOperation";
	}

	private String _processInsertExpr(Element updatingExprElement) throws XMLStreamException {
		String actionArgument = updatingExprElement.getElementsByTagName("InsertExprTargetChoice").item(0)
				.getTextContent();
		Element sourceExprElement = (Element) updatingExprElement.getElementsByTagName("SourceExpr")
				.item(0);
		// replace . with $ua:context in insertExprElement
		NodeList contextItemExprElementsForSourceExpr = updatingExprElement
				.getElementsByTagName("ContextItemExpr");
		for (int i = 0, il = contextItemExprElementsForSourceExpr.getLength(); i < il; i++) {
			contextItemExprElementsForSourceExpr.item(i).getFirstChild().setTextContent("$ua:context");
		}

		String sourceExpr = sourceExprElement.getTextContent();
		String targetExpr = updatingExprElement.getElementsByTagName("TargetExpr").item(0).getTextContent();

		_writeEntryElement("insertAction", insertExprTargetChoiceValues.get(actionArgument));
		_writeEntryElement("insertSourceLocation", sourceExpr);
		_writeEntryElement("insertTargetLocation", targetExpr);

		return "ro.kuberam.oxygen.addonBuilder.operations.InsertOperation";
	}

	private String _processDeleteExpr(Element updatingExprElement) throws XMLStreamException {
		_writeEntryElement("elementLocation", updatingExprElement.getElementsByTagName("TargetExpr")
				.item(0).getTextContent());

		return "ro.kuberam.oxygen.addonBuilder.operations.DeleteOperation";
	}

	private String _processReplaceExpr(Element updatingExprElement) throws XMLStreamException {
		String actionArgument = "";
		NodeList replaceExprElementChildren = updatingExprElement.getChildNodes();
		int replaceExprElementChildrenNumber = replaceExprElementChildren.getLength();
		for (int i = 0, il = replaceExprElementChildrenNumber; i < il; i++) {
			Node actionArgumentToken = replaceExprElementChildren.item(i);
			if (actionArgumentToken.getNodeName().equals("TOKEN")) {
				actionArgument += actionArgumentToken.getTextContent() + " ";
			}
		}
		actionArgument = actionArgument.replace("replace ", "").replace(" with ", "");

		// replace . with $ua:context in replaceExprElement
		NodeList contextItemExprElementsForInsertExpr = updatingExprElement
				.getElementsByTagName("ContextItemExpr");
		for (int i = 0, il = contextItemExprElementsForInsertExpr.getLength(); i < il; i++) {
			contextItemExprElementsForInsertExpr.item(i).getFirstChild().setTextContent("$ua:context");
		}

		String sourceExpr = replaceExprElementChildren.item(replaceExprElementChildrenNumber - 1)
				.getTextContent();
		String targetExpr = updatingExprElement.getElementsByTagName("TargetExpr").item(0).getTextContent();

		_writeEntryElement("replaceAction", replaceExprTargetChoiceValues.get(actionArgument));
		_writeEntryElement("replaceSourceLocation", sourceExpr);
		_writeEntryElement("replaceTargetLocation", targetExpr);

		// ////////////////////////////////////////////////////////
		// _writeEntryElement("sourceLocation", "/*");
		// _writeEntryElement("targetLocation", ".");
		// _writeEntryElement("action", "Replace");
		// _writeEntryElement("script",
		// "<a xmlns=\"http://www.tei-c.org/ns/1.0\">{doc('content-models/usg-datalist.xml')/*/*[@value = /*:TEI/*:text[1]/*:body[1]/*:entry[1]/*:form[2]/*:usg[1]/@value]}</a>");
		// _writeEntryElement("script",
		// "declare variable $document := /; doc('content-models/usg-datalist.xml')/*/*[@value = $document/*:TEI/*:text[1]/*:body[1]/*:entry[1]/*:form[2]/*:usg[1]/@value]");
		// _writeEntryElement("script",
		// "declare variable $document := /; doc('content-models/usg-datalist.xml')/*/*[@value = $document/*:TEI/*:text[1]/*:body[1]/*:entry[1]/*:form[2]/*:usg[1]/@value]/@label/string()");
		// _writeEntryElement("script",
		// "declare variable $document := /; doc('content-models/usg-datalist.xml')/*/*[@value = $document/TEI/text[1]/body[1]/entry[1]/form[2]/usg[1]/@value]/@label/string()");
		// _writeEntryElement("script", "'acum'");
		//
		// _writeEntryElement("name", "test-attribute");
		// _writeEntryElement("elementLocation", ".");
		// _writeEntryElement("value", "test-value");
		// _writeEntryElement("editAttribute", "false");
		// _writeEntryElement("removeIfEmpty", "false");

		// check if source expression refers to an attribute
		checkXPathExpressionIsAttribute(sourceExpr);
		if (sourceExpr.endsWith("@.+")) {
			// System.out.println(sourceExpr);
		}

		// /////////////////////////////////////////////////////////

		return "ro.kuberam.oxygen.addonBuilder.operations.ReplaceOperation";
	}

	private void checkXPathExpressionIsAttribute(String xpathExpression) {
		Matcher isAttributePatternMatcher = isAttributePattern.matcher(xpathExpression);

	}

	private String _processNodeSelector(final String nodeSelector) {
		String processedNodeSelector = "";

		if (nodeSelector.contains("ua-dt:xpath-selector")) {
			processedNodeSelector = _xpath2css(_processStringLiteral(nodeSelector.replaceAll(
					"ua-dt:xpath-selector\\(", "").replaceAll("\\)$", "")));
		} else {
			processedNodeSelector = _processStringLiteral(nodeSelector.replaceAll("ua-dt:css-selector\\(",
					"").replaceAll("\\)$", ""));
		}

		return processedNodeSelector;
	}

	private String _xpath2css(final String xpathNodeSelector) {
		return xpathNodeSelector;
	}

	private String _processStringLiteral(final String functionArgument) {
		return functionArgument.replaceAll("^['\"]", "").replaceAll("['\"]$", "");
	}

	public static void main(String args[]) throws Exception {
		if (args.length == 0) {
			String frameworkId = "dlridev";
//			String oxygenInstallDir = "/home/claudius/oxygen/current";
			String oxygenInstallDir = "C:/Program Files/Oxygen XML Author 17";
			
//			File addonDirectory = new File(oxygenInstallDir + "/frameworks/" + frameworkId);
			File addonDirectory = new File(oxygenInstallDir + "/frameworks/" + frameworkId);
			
//			AddonBuilderPluginExtension.pluginInstallDir = new File(
//					"/home/claudius/.com.oxygenxml.author/extensions/v17.1/plugins/http___claudius108.users.sourceforge.net_repos_addon_builder_plugin_addon.xml/addon-builder-plugin");
			
			AddonBuilderPluginExtension.pluginInstallDir = new File(
					"C:/Users/claudius.teodorescu/AppData/Roaming/com.oxygenxml.author/extensions/v17.1/plugins/http___claudius108.users.sourceforge.net_repos_addon_builder_plugin_addon.xml/addon-builder-plugin");
			
			FrameworkGeneratingBridge bridge = new FrameworkGeneratingBridge();
			bridge.frameworkId = frameworkId;
			bridge.oxygenInstallDir = oxygenInstallDir;
			bridge._generateFramework(addonDirectory);
		} else {
			new Parser(new File(args[0]), args[1], new File(args[2]));
		}
	}

}

//"C:/Program Files/Oxygen XML Author 17\tools\ant\bin\ant" -f "C:\Users\claudius.teodorescu\AppData\Roaming\com.oxygenxml.author\extensions\v17.1\plugins\http___claudius108.users.sourceforge.net_repos_addon_builder_plugin_addon.xml\addon-builder-plugin\generate-framework\build-framework-structure.xml" build-framework -DoxygenAddonBuilder.frameworksDir="C:\Program Files\Oxygen XML Author 17\frameworks" -DoxygenAddonBuilder.frameworkId=dlridev -DoxygenAddonBuilder.pluginInstallDir="C:\Users\claudius.teodorescu\AppData\Roaming\com.oxygenxml.author\extensions\v17.1\plugins\http___claudius108.users.sourceforge.net_repos_addon_builder_plugin_addon.xml\addon-builder-plugin"