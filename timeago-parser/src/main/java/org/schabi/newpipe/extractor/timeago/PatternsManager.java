/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  javax.annotation.Nonnull
 *  javax.annotation.Nullable
 */
package org.schabi.newpipe.extractor.timeago;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.schabi.newpipe.extractor.timeago.PatternMap;
import org.schabi.newpipe.extractor.timeago.PatternsHolder;

public class PatternsManager {
    @Nullable
    public static PatternsHolder getPatterns(@Nonnull String languageCode, @Nullable String countryCode) {
        String targetLocalizationClassName = languageCode + (String)(countryCode == null || countryCode.isEmpty() ? "" : "_" + countryCode);
        return PatternMap.getPattern(targetLocalizationClassName);
    }
}
