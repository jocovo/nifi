/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.util;

import org.apache.commons.io.IOUtils;
import org.apache.nifi.cluster.protocol.StandardDataFlow;
import org.apache.nifi.controller.flow.VersionedDataflow;
import org.apache.nifi.controller.serialization.FlowFromDOMFactory;
import org.apache.nifi.flow.VersionedPort;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.util.file.FileUtils;
import org.apache.nifi.web.api.dto.PortDTO;
import org.apache.nifi.web.api.dto.PositionDTO;
import org.apache.nifi.xml.processing.ProcessingException;
import org.apache.nifi.xml.processing.parsers.StandardDocumentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Parses a flow and returns the root group id and root group ports.
 */
public class FlowParser {

    private static final Logger logger = LoggerFactory.getLogger(FlowParser.class);

    private static final String FLOW_XSD = "/FlowConfiguration.xsd";
    private static final byte XML_FIRST_CHARACTER = '<';

    private final Schema flowSchema;

    public FlowParser() throws SAXException {
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        flowSchema = schemaFactory.newSchema(FlowParser.class.getResource(FLOW_XSD));
    }

    /**
     * Extracts the root group id from the flow configuration file provided in nifi.properties, and extracts
     * the root group input ports and output ports, and their access controls.
     *
     */
    public FlowInfo parse(final File flowConfigurationFile) {
        if (flowConfigurationFile == null) {
            logger.debug("Flow Configuration file was null");
            return null;
        }

        // if the flow doesn't exist or is 0 bytes, then return null
        final Path flowPath = flowConfigurationFile.toPath();
        try {
            if (!Files.exists(flowPath) || Files.size(flowPath) == 0) {
                logger.warn("Flow Configuration does not exist or was empty");
                return null;
            }
        } catch (IOException e) {
            logger.error("An error occurred determining the size of the Flow Configuration file");
            return null;
        }

        // otherwise create the appropriate input streams to read the file
        try (final InputStream in = Files.newInputStream(flowPath, StandardOpenOption.READ);
             final InputStream gzipIn = new GZIPInputStream(in)) {

            final byte[] flowBytes = IOUtils.toByteArray(gzipIn);
            if (flowBytes == null || flowBytes.length == 0) {
                logger.warn("Could not extract root group id because Flow Configuration File was empty");
                return null;
            }

            if (isXml(flowBytes)) {
                return parseXml(flowBytes);
            }

            return parseJson(flowBytes);
        } catch (final SAXException | ParserConfigurationException | IOException ex) {
            logger.error("Unable to parse flow {}", flowPath.toAbsolutePath(), ex);
            return null;
        }
    }

    private boolean isXml(final byte[] contents) {
        if (contents == null || contents.length == 0) {
            return false;
        }

        final byte firstByte = contents[0];
        return firstByte == XML_FIRST_CHARACTER;
    }

    private FlowInfo parseXml(final byte[] flowBytes) throws ParserConfigurationException, IOException, SAXException {
        // create validating document builder
        final ErrorHandler errorHandler = new LoggingXmlParserErrorHandler("Flow Configuration", logger);
        final StandardDocumentProvider documentProvider = new StandardDocumentProvider();
        documentProvider.setSchema(flowSchema);
        documentProvider.setNamespaceAware(true);
        documentProvider.setErrorHandler(errorHandler);

        // parse the flow
        final Document document = documentProvider.parse(new ByteArrayInputStream(flowBytes));

        // extract the root group id
        final Element rootElement = document.getDocumentElement();

        final Element rootGroupElement = (Element) rootElement.getElementsByTagName("rootGroup").item(0);
        if (rootGroupElement == null) {
            logger.warn("rootGroup element not found in Flow Configuration file");
            return null;
        }

        final Element rootGroupIdElement = (Element) rootGroupElement.getElementsByTagName("id").item(0);
        if (rootGroupIdElement == null) {
            logger.warn("id element not found under rootGroup in Flow Configuration file");
            return null;
        }

        final String rootGroupId = rootGroupIdElement.getTextContent();

        final List<PortDTO> ports = new ArrayList<>();
        ports.addAll(getPorts(rootGroupElement, "inputPort"));
        ports.addAll(getPorts(rootGroupElement, "outputPort"));

        return new FlowInfo(rootGroupId, ports);
    }

    private FlowInfo parseJson(final byte[] flowBytes) {
        final StandardDataFlow standardDataFlow = new StandardDataFlow(flowBytes, new byte[0], null, Collections.emptySet());
        final VersionedDataflow dataflow = standardDataFlow.getVersionedDataflow();
        final VersionedProcessGroup rootGroup = dataflow.getRootGroup();

        if (rootGroup == null) {
            return null;
        }

        final List<PortDTO> ports = new ArrayList<>();
        mapPorts(rootGroup.getInputPorts(), ports);
        mapPorts(rootGroup.getOutputPorts(), ports);

        return new FlowInfo(rootGroup.getIdentifier(), ports);
    }

    private void mapPorts(final Set<VersionedPort> versionedPorts, final List<PortDTO> portDtos) {
        if (versionedPorts == null || versionedPorts.isEmpty()) {
            return;
        }

        for (final VersionedPort port : versionedPorts) {
            final PortDTO portDto = mapPort(port);
            portDtos.add(portDto);
        }
    }

    private PortDTO mapPort(final VersionedPort port) {
        final PortDTO dto = new PortDTO();
        dto.setAllowRemoteAccess(port.isAllowRemoteAccess());
        dto.setComments(port.getComments());
        dto.setConcurrentlySchedulableTaskCount(port.getConcurrentlySchedulableTaskCount());
        dto.setId(port.getIdentifier());
        dto.setName(port.getName());
        dto.setParentGroupId(port.getGroupIdentifier());
        dto.setPosition(new PositionDTO(port.getPosition().getX(), port.getPosition().getY()));
        dto.setState(port.getScheduledState().name());
        dto.setType(port.getType().name());
        return dto;
    }

    /**
     * Generates a {@link Document} from the flow configuration file provided
     */
    public Document parseDocument(final File flowConfigurationFile) {
        if (flowConfigurationFile == null) {
            logger.debug("Flow Configuration file was null");
            return null;
        }

        // if the flow doesn't exist or is 0 bytes, then return null
        final Path flowPath = flowConfigurationFile.toPath();
        try {
            if (!Files.exists(flowPath) || Files.size(flowPath) == 0) {
                logger.warn("Flow Configuration does not exist or was empty");
                return null;
            }
        } catch (IOException e) {
            logger.error("An error occurred determining the size of the Flow Configuration file");
            return null;
        }

        // otherwise create the appropriate input streams to read the file
        try (final InputStream in = Files.newInputStream(flowPath, StandardOpenOption.READ);
             final InputStream gzipIn = new GZIPInputStream(in)) {

            final byte[] flowBytes = IOUtils.toByteArray(gzipIn);
            if (flowBytes == null || flowBytes.length == 0) {
                logger.warn("Could not extract root group id because Flow Configuration File was empty");
                return null;
            }

            // create validating document builder
            final StandardDocumentProvider documentProvider = new StandardDocumentProvider();
            documentProvider.setErrorHandler(new LoggingXmlParserErrorHandler("Flow Configuration", logger));
            documentProvider.setSchema(flowSchema);
            documentProvider.setNamespaceAware(true);
            return documentProvider.parse(new ByteArrayInputStream(flowBytes));
        } catch (final ProcessingException | IOException e) {
            logger.error("Unable to parse flow {}", flowPath.toAbsolutePath(), e);
            return null;
        }
    }

    /**
     * Gets the ports that are direct children of the given element.
     *
     * @param element the element containing ports
     * @param type the type of port to find (inputPort or outputPort)
     * @return a list of PortDTOs representing the found ports
     */
    private List<PortDTO> getPorts(final Element element, final String type) {
        final List<PortDTO> ports = new ArrayList<>();

        // add input ports
        final List<Element> portNodeList = getChildrenByTagName(element, type);
        for (final Element portElement : portNodeList) {
            final PortDTO portDTO = FlowFromDOMFactory.getPort(portElement);
            portDTO.setType(type);
            ports.add(portDTO);
        }

        return  ports;
    }

    /**
     * Writes a given XML Flow out to the specified path.
     *
     * @param flowDocument flowDocument of the associated XML content to write to disk
     * @param flowXmlPath  path on disk to write the flow
     * @throws IOException if there are issues in accessing the target destination for the flow
     * @throws TransformerException if there are issues in the xml transformation process
     */
    public void writeFlow(final Document flowDocument, final Path flowXmlPath) throws IOException, TransformerException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final Source xmlSource = new DOMSource(flowDocument);
        final Result outputTarget = new StreamResult(outputStream);
        TransformerFactory.newInstance().newTransformer().transform(xmlSource, outputTarget);
        final InputStream is = new ByteArrayInputStream(outputStream.toByteArray());

        try (final OutputStream output = Files.newOutputStream(flowXmlPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
             final OutputStream gzipOut = new GZIPOutputStream(output)) {
            FileUtils.copy(is, gzipOut);
        }
    }

    /**
     * Finds child elements with the given tagName.
     *
     * @param element the parent element
     * @param tagName the child element name to find
     * @return a list of matching child elements
     */
    public static List<Element> getChildrenByTagName(final Element element, final String tagName) {
        final List<Element> matches = new ArrayList<>();
        final NodeList nodeList = element.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            final Node node = nodeList.item(i);
            if (!(node instanceof Element)) {
                continue;
            }

            final Element child = (Element) nodeList.item(i);
            if (child.getNodeName().equals(tagName)) {
                matches.add(child);
            }
        }
        return matches;
    }
}
