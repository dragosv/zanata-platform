/*
 * Copyright 2018, Red Hat, Inc. and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.zanata.adapter

import net.sf.okapi.common.Event
import net.sf.okapi.common.EventType
import net.sf.okapi.common.IParameters
import net.sf.okapi.common.ISkeleton
import net.sf.okapi.common.exceptions.OkapiIOException
import net.sf.okapi.common.exceptions.OkapiUnexpectedResourceTypeException
import net.sf.okapi.common.filterwriter.GenericContent
import net.sf.okapi.common.filterwriter.IFilterWriter
import net.sf.okapi.common.resource.RawDocument
import net.sf.okapi.common.resource.TextContainer
import net.sf.okapi.common.resource.TextUnit
import net.sf.okapi.common.skeleton.GenericSkeleton
import net.sf.okapi.filters.xliff.Parameters
import net.sf.okapi.filters.xliff.XLIFFFilter
import org.apache.commons.io.FilenameUtils
import org.apache.commons.lang3.StringUtils
import org.zanata.common.ContentState
import org.zanata.common.ContentType
import org.zanata.common.HasContents
import org.zanata.common.LocaleId
import org.zanata.exception.FileFormatAdapterException
import org.zanata.model.HDocument
import org.zanata.rest.dto.extensions.comment.SimpleComment
import org.zanata.rest.dto.extensions.gettext.PotEntryHeader
import org.zanata.rest.dto.resource.Resource
import org.zanata.rest.dto.resource.TextFlow
import org.zanata.rest.dto.resource.TextFlowTarget
import org.zanata.rest.dto.resource.TranslationsResource
import java.net.URI
import java.util.*
import java.util.regex.Pattern

/**
 * Adapter to handle Xliff 1.2 documents.
 * @see [JSON Specification](http://docs.oasis-open.org/xliff/v1.2/os/xliff-core.html)
 *
 * @author Dragos Varovici [dvarovici.work@gmail.com](mailto:dvarovici.work@gmail.com)
 */

class Xliff12Adapter : OkapiFilterAdapter(prepareFilter(), OkapiFilterAdapter.IdSource.textUnitId, false) {

    override val rawTranslationUploadAvailable: Boolean
        get() = true

    // ExtraComment takes precedence
    private val COMMENT_REGEX = "<extracomment>(.+)</extracomment>|<comment>(.+)</comment>"

    override fun updateParamsWithDefaults(params: IParameters) {
        val p = params as Parameters
        p.allowEmptyTargets = true
    }

    @Throws(FileFormatAdapterException::class, IllegalArgumentException::class)
    override fun parseDocumentFile(options: FileFormatAdapter.ParserOptions): Resource {
        val filter = filter
        val document = Resource()
        document.lang = options.locale
        document.contentType = ContentType.TextPlain
        updateParamsWithDefaults(filter.parameters)
        val resources = document.textFlows
        val addedResources = HashMap<String, HasContents>()
        val rawDoc = RawDocument(options.rawFile, "UTF-8",
                net.sf.okapi.common.LocaleId.fromString("en"))
        if (rawDoc.targetLocale == null) {
            rawDoc.targetLocale = net.sf.okapi.common.LocaleId.EMPTY
        }
        try {
            filter.open(rawDoc)
            val subDocName = ""
            // TS can contain similar source strings in different contexts
            var context = ""
            while (filter.hasNext()) {
                val event = filter.next()
                if (isStartContext(event)) {
                    context = getContext(event)
                } else if (isEndContext(event)) {
                    context = ""
                } else if (event.eventType == EventType.TEXT_UNIT) {
                    val tu = event.resource as TextUnit
                    if (!tu.source.isEmpty && tu.isTranslatable) {
                        val content = getTranslatableText(tu)
                        if (!content.isEmpty()) {
                            var tf = processTextFlow(tu, context, content,
                                    subDocName, options.locale)
                            if (!addedResources.containsKey(tf.id)) {
                                tf = addExtensions(tf, tu, context)
                                addedResources[tf.id] = tf
                                resources.add(tf)
                            }
                        }
                    }
                }
            }
        } catch (e: OkapiIOException) {
            throw FileFormatAdapterException("Unable to parse document", e)
        } finally {
            filter.close()
        }
        return document
    }

    private fun addExtensions(textFlow: TextFlow, textUnit: TextUnit,
                              context: String): TextFlow {
        if (StringUtils.isNotBlank(context)) {
            val potEntryHeader = PotEntryHeader()
            potEntryHeader.context = context
            textFlow.getExtensions(true).add(potEntryHeader)
        }
        val commentPattern = Pattern.compile(COMMENT_REGEX)
        val matcher = commentPattern.matcher(textUnit.skeleton.toString())
        if (matcher.find()) {
            if (StringUtils.isNotBlank(matcher.group(1))) {
                textFlow.getExtensions(true)
                        .add(SimpleComment(matcher.group(1)))
            } else if (StringUtils.isNotBlank(matcher.group(2))) {
                textFlow.getExtensions(true)
                        .add(SimpleComment(matcher.group(2)))
            }
        }
        return textFlow
    }

    override fun generateTranslationFilename(document: HDocument,
                                             locale: String): String {
        val srcExt = FilenameUtils.getExtension(document.name)
        val documentType = document.rawDocument!!.type
        val transExt = documentType.extensions[srcExt]
        if (StringUtils.isEmpty(transExt)) {
            log.warn("Adding missing TS extension to generated filename")
            return document.name + "_" + locale + ".ts"
        }
        return (FilenameUtils.removeExtension(document.name) + "_" + locale
                + "." + transExt)
    }

    public override fun generateTranslatedFile(originalFile: URI,
                                               translations: Map<String, TextFlowTarget>,
                                               localeId: net.sf.okapi.common.LocaleId, writer: IFilterWriter,
                                               params: String, approvedOnly: Boolean) {
        val rawDoc = RawDocument(originalFile, "UTF-8",
                net.sf.okapi.common.LocaleId.fromString("en"))
        if (rawDoc.targetLocale == null) {
            rawDoc.targetLocale = localeId
        }
        val encounteredIds = ArrayList<String>()
        val filter = filter
        updateParamsWithDefaults(filter.parameters)
        try {
            filter.open(rawDoc)
            // TS can contain similar source strings in different contexts
            var context = ""
            while (filter.hasNext()) {
                val event = filter.next()
                if (event.isDocumentPart && event.documentPart.hasProperty("language")) {
                    event.documentPart.skeleton = replaceLocaleInDocPart(event, localeId)
                } else if (isStartContext(event)) {
                    context = getContext(event)
                } else if (isEndContext(event)) {
                    context = ""
                } else if (event.eventType == EventType.TEXT_UNIT) {
                    processTranslation(event, context, localeId,
                            translations, encounteredIds, approvedOnly)
                }
                writer.handleEvent(event)
            }
        } catch (e: OkapiIOException) {
            throw FileFormatAdapterException(
                    "Unable to generate translated document from original", e)
        } finally {
            filter.close()
            writer.close()
        }
    }

    /**
     * Process a textunit event (TS message), adding any available translations
     *
     * @param event Okapi event of type TextUnit
     * @param context parent context of the TS message
     * @param localeId target locale
     * @param translations list of available translations
     * @param encounteredIds text unit ids already encountered
     * @param approvedOnly whether to include Translated entries
     */
    private fun processTranslation(event: Event, context: String,
                                   localeId: net.sf.okapi.common.LocaleId,
                                   translations: Map<String, TextFlowTarget>,
                                   encounteredIds: MutableList<String>,
                                   approvedOnly: Boolean) {
        val tu = event.resource as TextUnit
        if (!tu.source.isEmpty && tu.isTranslatable) {
            val translatable = getTranslatableText(tu)
            // Ignore if the source is empty
            if (!translatable.isEmpty()) {
                val id = getIdFor(tu, context + translatable, "")
                val tft = translations[id]
                if (tft != null && !encounteredIds.contains(id)) {
                    // Dismiss duplicate numerusforms
                    encounteredIds.add(id)
                    for (translated in tft.contents) {
                        val finished = usable(tft.state, approvedOnly)
                        val propVal = if (finished) "yes" else "no"
                        // Okapi will map approved=no to type=unfinished in the .TS file
                        tu.getTargetProperty(localeId, "approved").value = propVal
                        // TODO: Find a method of doing this in
                        // one object, not a loop
                        tu.setTargetContent(localeId,
                                GenericContent.fromLetterCodedToFragment(
                                        translated,
                                        tu.source.firstContent.clone(),
                                        true, true))
                    }
                }
            }
        }
    }

    private fun usable(state: ContentState, approvedOnly: Boolean): Boolean {
        return state.isApproved || !approvedOnly && state.isTranslated
    }

    /**
     * Replace the language="en_US" with a target localeId
     *
     * @param event the DocumentPart event of a TS file header
     * @param localeId the desired target locale
     * @return a GenericSkeleton with the language replaced
     */
    internal fun replaceLocaleInDocPart(event: Event,
                                        localeId: net.sf.okapi.common.LocaleId): ISkeleton {
        var part: String
        try {
            part = event.documentPart.skeleton.clone().toString()
        } catch (okapiException: OkapiUnexpectedResourceTypeException) {
            log.error("Unexpected Qt TS event type: {}, in {}",
                    event.eventType, event.resource.skeleton)
            throw FileFormatAdapterException("Qt TS Adapter error")
        }

        try {
            part = part.replace("\\slanguage\\s*=\\s*\"[\\w\\d@.-]*\"".toRegex(), " language=\"" + localeId.toBCP47() + "\"")
        } catch (exception: Exception) {
            throw FileFormatAdapterException("Invalid target locale")
        }

        return GenericSkeleton(part)
    }

    override fun getTranslatableText(tu: TextUnit): String {
        return tu.source.firstContent.text
    }

    private fun getTranslatedText(tc: TextContainer): String {
        return tc.firstContent.text
    }

    /**
     * Create a TextFlow from Qt ts TextUnit with context and plurals. ID is
     * derived from a concatenation of context and content.
     *
     * @param tu
     * original textunit
     * @param context
     * ts source context
     * @param content
     * text unit content
     * @param subDocName
     * subdocument name
     * @param sourceLocale
     * source locale
     * @return
     */
    protected fun processTextFlow(tu: TextUnit, context: String,
                                  content: String, subDocName: String, sourceLocale: LocaleId): TextFlow {
        val tf = TextFlow(getIdFor(tu, context + content, subDocName),
                sourceLocale)
        if (tu.hasProperty("numerus") && tu.getProperty("numerus").value
                        .equals("yes", ignoreCase = true)) {
            tf.isPlural = true
            // Qt TS uses a single message for singular and plural form
            tf.setContents(content, content)
        } else {
            tf.isPlural = false
            tf.setContents(content)
        }
        return tf
    }

    public override fun parseTranslationFile(rawDoc: RawDocument,
                                             params: String): TranslationsResource {
        val transRes = TranslationsResource()
        val translations = transRes.textFlowTargets
        val addedResources = HashMap<String, HasContents>()
        val filter = filter
        updateParamsWithDefaults(filter.parameters)
        try {
            filter.open(rawDoc)
            val subDocName = ""
            // TS can contain similar source strings in different contexts
            var context = ""
            while (filter.hasNext()) {
                val event = filter.next()
                if (isStartContext(event)) {
                    context = getContext(event)
                } else if (isEndContext(event)) {
                    context = ""
                } else if (event.eventType == EventType.TEXT_UNIT) {
                    val tu = event.resource as TextUnit
                    if (!tu.source.isEmpty && tu.isTranslatable) {
                        val content = getTranslatableText(tu)
                        val translation = tu.getTarget(rawDoc.targetLocale)
                        if (!content.isEmpty()) {
                            val tft = TextFlowTarget(getIdFor(tu,
                                    context + content, subDocName))
                            // TODO: Change this
                            tft.state = ContentState.Translated
                            val resId = tft.resId
                            if (addedResources.containsKey(resId)) {
                                val currentStrings = ArrayList(addedResources[resId]?.getContents())
                                currentStrings
                                        .add(getTranslatedText(translation))
                                tft.setContents(currentStrings)
                            } else {
                                tft.setContents(getTranslatedText(translation))
                            }
                            addedResources[tft.resId] = tft
                            translations.add(tft)
                        }
                    }
                }
            }
        } catch (e: OkapiIOException) {
            throw FileFormatAdapterException(
                    "Unable to parse translation file", e)
        } finally {
            filter.close()
        }
        return transRes
    }

    private fun isStartContext(event: Event): Boolean {
        return event.isStartGroup && event.startGroup.toString()
                .toLowerCase().contains("<context")
    }

    private fun isEndContext(event: Event): Boolean {
        return event.isEndGroup && event.endGroup.toString()
                .toLowerCase().contains("</context")
    }

    private fun getContext(event: Event): String {
        // TODO: Numerusform bug workaround, remove when fixed
        val startGroup = event.startGroup
        run {
            val pattern = Pattern.compile("<name>(.+)</name>", Pattern.MULTILINE)
            val matcher = pattern.matcher(startGroup.toString())
            if (startGroup.name == null && matcher.find()) {
                log.info("Qt ts context bug encountered, returning {} from {}",
                        matcher.group(1), startGroup.toString())
                return matcher.group(1)
            }
        }
        return if (startGroup.name == null)
            ""
        else
            event.startGroup.name
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(Xliff12Adapter::class.java)

        private fun prepareFilter(): XLIFFFilter {
            return XLIFFFilter()
        }
    }
}

