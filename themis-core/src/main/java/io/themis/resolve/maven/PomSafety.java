package io.themis.resolve.maven;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;

/**
 * Rejects a project POM that carries a DOCTYPE before any Maven code reads it.
 * <p>
 * The scanned project is untrusted input. Maven's own XML reader does not expand external
 * entities, but refusing DTDs outright keeps that guarantee independent of the parser version.
 * Only the project's own POMs (root and modules) are checked here; POMs fetched from
 * repositories are parsed by Maven Resolver itself.
 */
final class PomSafety {

    /** Errors are reported through the thrown exception, not printed as "[Fatal Error]" on stderr. */
    private static final ErrorHandler SILENT = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {
        }

        @Override
        public void error(SAXParseException e) throws SAXParseException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXParseException {
            throw e;
        }
    };

    private PomSafety() {
    }

    static void requireNoDoctype(Path pom) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setExpandEntityReferences(false);
            DocumentBuilder builder = f.newDocumentBuilder();
            builder.setErrorHandler(SILENT);
            builder.parse(pom.toFile());
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse " + pom + ": " + e.getMessage(), e);
        }
    }
}
