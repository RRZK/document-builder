package com.craigburke.document.builder

import com.craigburke.document.builder.render.ParagraphElement
import com.craigburke.document.builder.render.TableElement
import com.craigburke.document.core.EmbeddedFont
import com.craigburke.document.core.HeaderFooterOptions
import com.craigburke.document.core.PageBreak
import com.craigburke.document.core.builder.RenderState
import groovy.transform.InheritConstructors
import groovy.xml.MarkupBuilder

import com.craigburke.document.core.builder.DocumentBuilder
import com.craigburke.document.core.Document
import com.craigburke.document.core.TextBlock
import com.craigburke.document.core.Table
import com.craigburke.document.core.Image

import org.apache.pdfbox.pdmodel.common.PDMetadata

/**
 * Builder for PDF documents
 * @author Craig Burke
 */
@InheritConstructors
class PdfDocumentBuilder extends DocumentBuilder {

    PdfDocument pdfDocument

	void initializeDocument(Document document, OutputStream out) {
        pdfDocument = new PdfDocument(document)
        pdfDocument.x = document.margin.left
        pdfDocument.y = document.margin.top
        document.element = pdfDocument
    }

    def addEmbeddedFont = { EmbeddedFont embeddedFont ->
        PdfFont.addFont(pdfDocument.pdDocument, embeddedFont)
    }

    def addPageBreakToDocument = { PageBreak pageBreak, Document document ->
        pdfDocument.addPage()
    }

	def onTextBlockComplete = { TextBlock paragraph ->
        if (renderState == RenderState.PAGE && paragraph.parent instanceof Document) {
            int pageWidth = document.width - document.margin.left - document.margin.right
            int maxLineWidth = pageWidth - paragraph.margin.left - paragraph.margin.right
            int renderStartX = document.margin.left + paragraph.margin.left

            pdfDocument.x = renderStartX
            pdfDocument.scrollDownPage(paragraph.margin.top)

            ParagraphElement paragraphElement = new ParagraphElement(paragraph, renderStartX, maxLineWidth)

            while (!paragraphElement.fullyParsed) {
                paragraphElement.parseUntilHeight(pdfDocument.remainingPageHeight)
                paragraphElement.render(document, renderState)
                if (!paragraphElement.fullyParsed) {
                    pdfDocument.addPage()
                }
            }
            pdfDocument.scrollDownPage(paragraph.margin.bottom)
        }
    }

    def onTableComplete = { Table table ->
        if (renderState == RenderState.PAGE) {
            pdfDocument.x = table.margin.left + document.margin.left
            pdfDocument.scrollDownPage(table.margin.top)
            TableElement tableElement = new TableElement(table, pdfDocument.x)
            while (!tableElement.fullyParsed) {
                tableElement.parseUntilHeight(pdfDocument.remainingPageHeight)
                tableElement.render(document, renderState)
                if (!tableElement.fullyParsed) {
                    pdfDocument.addPage()
                }
            }
            pdfDocument.scrollDownPage(table.margin.bottom)
        }
    }

	void writeDocument(Document document, OutputStream out) {
        addHeaderFooter()
		addMetadata()

		pdfDocument.contentStream?.close()
        pdfDocument.pdDocument.save(out)
        pdfDocument.pdDocument.close()
	}

    private void addHeaderFooter() {
        int pageCount = pdfDocument.pages.size()
        def options = new HeaderFooterOptions(pageCount:pageCount, dateGenerated:new Date())

        (1..pageCount).each { int pageNumber ->
            pdfDocument.pageNumber = pageNumber
            options.pageNumber = pageNumber

            if (document.header) {
                renderState = RenderState.HEADER
                def header = document.header(options)
                renderHeaderFooter(header)
            }
            if (document.footer) {
                renderState = RenderState.FOOTER
                def footer = document.footer(options)
                renderHeaderFooter(footer)
            }
        }

        renderState = RenderState.PAGE
    }

    private void renderHeaderFooter(headerFooter) {
        int xStart = document.margin.left + headerFooter.margin.left

        def renderer
        if (headerFooter instanceof TextBlock) {
            renderer = new ParagraphElement(headerFooter, xStart, document.width)
        }
        else if (headerFooter instanceof Table) {
            renderer = new TableElement(headerFooter, xStart)
        }

        if (renderState == RenderState.HEADER) {
            pdfDocument.y = headerFooter.margin.top
        }
        else {
            pdfDocument.y = pdfDocument.pageBottomY + document.margin.bottom - renderer.totalHeight
        }

        renderer.render(document, renderState)
    }

	private void addMetadata() {
		ByteArrayOutputStream xmpOut = new ByteArrayOutputStream()
		def xml = new MarkupBuilder(xmpOut.newWriter())

		xml.document(marginTop:"${document.margin.top}", marginBottom:"${document.margin.bottom}",
                marginLeft:"${document.margin.left}", marginRight:"${document.margin.right}") {

			delegate = xml
			resolveStrategy = Closure.DELEGATE_FIRST

			document.children.each { child ->
                switch (child.getClass()) {
                    case TextBlock:
                        addParagraphToMetadata(delegate, child)
                        break
                    case Table:
                        addTableToMetadata(delegate, child)
                        break
                }
			}
		}

		def catalog = pdfDocument.pdDocument.documentCatalog
        InputStream inputStream = new ByteArrayInputStream(xmpOut.toByteArray())

		PDMetadata metadata = new PDMetadata(pdfDocument.pdDocument, inputStream, false)
		catalog.metadata = metadata
	}

    private void addParagraphToMetadata(builder, TextBlock paragraphNode) {
        builder.paragraph(marginTop:"${paragraphNode.margin.top}",
                marginBottom:"${paragraphNode.margin.bottom}",
                marginLeft:"${paragraphNode.margin.left}",
                marginRight:"${paragraphNode.margin.right}") {
                    paragraphNode.children?.findAll { it.getClass() == Image }.each {
                        builder.image()
                    }
                }
    }

    private void addTableToMetadata(builder, Table tableNode) {

        builder.table(columns:tableNode.columns, width:tableNode.width, borderSize:tableNode.border.size) {

            delegate = builder
            resolveStrategy = Closure.DELEGATE_FIRST

            tableNode.children.each {
                def cells = it.children
                row {
                    cells.each {
                        cell(width:"${it.width ?: 0}")
                    }
                }
            }
        }
    }

}
