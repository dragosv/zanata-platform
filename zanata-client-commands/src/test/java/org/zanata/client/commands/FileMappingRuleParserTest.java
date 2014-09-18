/*
 * Copyright 2014, Red Hat, Inc. and individual contributors
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

package org.zanata.client.commands;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.zanata.client.commands.FileMappingRuleParser.*;

import org.hamcrest.Matchers;
import org.junit.Test;
import org.zanata.client.config.FileMappingRule;
import org.zanata.client.config.LocaleMapping;
import org.zanata.common.ProjectType;

public class FileMappingRuleParserTest {

    @Test
    public void canCheckSyntaxErrorInTheRule() {
        assertThat("unbalanced brace", isRuleValid("{a"), equalTo(false));
        assertThat("unbalanced brace", isRuleValid("a}"), equalTo(false));
        assertThat("missing brace", isRuleValid("a"), equalTo(false));
        assertThat("invalid placeholder",
                isRuleValid("{a}"), equalTo(false));
        assertThat("missing mandatory placeholder",
                isRuleValid("{path}"), equalTo(false));
        assertThat(isRuleValid(
                "{path}/{locale_with_underscore}.po"), equalTo(true));
        assertThat(isRuleValid(
                "{path}/../{locale}/{filename}.po"), equalTo(true));
    }

    @Test
    public void willReturnTransFileRelativePath() {
        assertThat(getTransFile("pot/message.pot", "fr",
                "{path}/../{locale}/{filename}.po", ProjectType.Podir),
                Matchers.equalTo("fr/message.po"));
        assertThat(getTransFile("./message.pot", "fr",
                "{path}/{locale_with_underscore}.po", ProjectType.Gettext),
                Matchers.equalTo("fr.po"));
        assertThat(getTransFile("a/path/message.odt", "de-DE",
                "{path}/{locale_with_underscore}_{filename}.{extension}",
                        ProjectType.File),
                Matchers.equalTo("a/path/de_DE_message.odt"));
    }

    @Test
    public void ifNoPatternWillUseProjectType() {
        FileMappingRuleParser parser =
                new FileMappingRuleParser(
                        new FileMappingRule(null,
                                "{path}/{locale_with_underscore}.po"),
                        ProjectType.Gettext);
        assertThat(parser.getRelativePathFromRule(
                TransFileResolver.QualifiedSrcDocName.from("message.pot"),
                new LocaleMapping("zh")), Matchers.equalTo("zh.po"));
    }

    private String getTransFile(String sourceFile, String locale, String rule,
            ProjectType projectType) {
        FileMappingRuleParser parser = new FileMappingRuleParser(
            new FileMappingRule("**/*", rule), projectType);
        return parser.getRelativePathFromRule(
                TransFileResolver.QualifiedSrcDocName.from(sourceFile),
                new LocaleMapping(locale));
    }

}
