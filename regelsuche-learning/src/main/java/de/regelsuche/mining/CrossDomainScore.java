package de.regelsuche.mining;

import java.util.Locale;

/** Rewards recurrence of the same structure across mathematical domains. */
public final class CrossDomainScore implements InterestingnessScoringModule {
    @Override
    public String name() {
        return "crossDomain";
    }

    @Override
    public double score(InterestingnessScoringContext context) {
        long domains = context.candidate().supportingPaths().stream()
            .map(CrossDomainScore::domainPrefix)
            .filter(domain -> !domain.isBlank())
            .distinct()
            .count();
        return domains <= 1 ? domains : 1.0 + Math.log(domains);
    }

    private static String domainPrefix(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String trimmed = path.trim().toLowerCase(Locale.ROOT);
        int separator = firstSeparator(trimmed);
        return separator <= 0 ? "" : trimmed.substring(0, separator);
    }

    private static int firstSeparator(String value) {
        int best = -1;
        for (char separator : new char[] {':', '/', '#', '-'}) {
            int index = value.indexOf(separator);
            if (index > 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }
}
