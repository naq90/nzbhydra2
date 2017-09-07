package org.nzbhydra.searching;

import com.google.common.base.Strings;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multiset.Entry;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.nzbhydra.config.BaseConfig;
import org.nzbhydra.config.ConfigProvider;
import org.nzbhydra.config.IndexerConfig;
import org.nzbhydra.config.SearchSourceRestriction;
import org.nzbhydra.logging.LoggingMarkers;
import org.nzbhydra.searching.searchrequests.SearchRequest;
import org.nzbhydra.searching.searchrequests.SearchRequest.SearchSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ResultAcceptor {

    private static final Logger logger = LoggerFactory.getLogger(ResultAcceptor.class);

    private static final Pattern TITLE_PATTERN = Pattern.compile("(\\w[\\w']*\\w|\\w)");
    private Marker MARKER = MarkerFactory.getMarker(LoggingMarkers.RESULT_ACCEPTOR.name());

    private Map<String, List<String>> titleWordCache = new HashMap<>();

    @Autowired
    private ConfigProvider configProvider;

    public AcceptorResult acceptResults(List<SearchResultItem> items, SearchRequest searchRequest, IndexerConfig indexerConfig) {
        BaseConfig baseConfig = configProvider.getBaseConfig();
        titleWordCache = new HashMap<>();
        List<SearchResultItem> acceptedResults = new ArrayList<>();
        Multiset<String> reasonsForRejection = HashMultiset.create();
        for (SearchResultItem item : items) {

            if (!checkForNeededAttributesSuccessfullyMapped(reasonsForRejection, item)) {
                continue;
            }
            if (!checkForPassword(reasonsForRejection, item)) {
                continue;
            }
            if (!checkForForbiddenGroup(reasonsForRejection, item)) {
                continue;
            }
            if (!checkForForbiddenPoster(reasonsForRejection, item)) {
                continue;
            }
            if (!checkForSize(searchRequest, reasonsForRejection, item)) {
                continue;
            }
            if (!checkForAge(searchRequest, reasonsForRejection, item)) {
                continue;
            }
            if (!checkForCategory(searchRequest, reasonsForRejection, item)) {
                continue;
            }
            if (!checkForCategoryDisabledForIndexer(searchRequest, reasonsForRejection, item)) {
                continue;
            }


            //Forbidden words from query
            if (!checkForForbiddenWords(indexerConfig, reasonsForRejection, searchRequest.getInternalData().getForbiddenWords(), item)) {
                continue;
            }

            if (!checkRequiredWords(reasonsForRejection, searchRequest.getInternalData().getRequiredWords(), item)) {
                continue;
            }

            //Globally configured
            boolean applyWordAndRegexRestrictions = baseConfig.getSearching().getApplyRestrictions() == SearchSourceRestriction.BOTH || Objects.equals(searchRequest.getSource().name(), baseConfig.getSearching().getApplyRestrictions().name());
            if (applyWordAndRegexRestrictions) {
                if (!checkRegexes(item, reasonsForRejection, baseConfig.getSearching().getRequiredRegex().orElse(null), baseConfig.getSearching().getForbiddenRegex().orElse(null))) {
                    continue;
                }
                if (!checkRequiredWords(reasonsForRejection, baseConfig.getSearching().getRequiredWords(), item)) {
                    continue;
                }
                if (!checkForForbiddenWords(indexerConfig, reasonsForRejection, baseConfig.getSearching().getForbiddenWords(), item)) {
                    continue;
                }
            }

            //Per category
            applyWordAndRegexRestrictions = item.getCategory().getApplyRestrictionsType() == SearchSourceRestriction.BOTH || Objects.equals(searchRequest.getSource().name(), item.getCategory().getApplyRestrictionsType().name());
            if (applyWordAndRegexRestrictions) {
                if (!checkRegexes(item, reasonsForRejection, item.getCategory().getRequiredRegex().orElse(null), item.getCategory().getForbiddenRegex().orElse(null))) {
                    continue;
                }
                if (!checkRequiredWords(reasonsForRejection, item.getCategory().getRequiredWords(), item)) {
                    continue;
                }
                if (!checkForForbiddenWords(indexerConfig, reasonsForRejection, item.getCategory().getForbiddenWords(), item)) {
                    continue;
                }
            }

            acceptedResults.add(item);
        }
        if (acceptedResults.size() < items.size()) {
            logger.info("Rejected {} out of {} search results from indexer {}", items.size() - acceptedResults.size(), items.size(), indexerConfig.getName());
            for (Entry<String> entry : reasonsForRejection.entrySet()) {
                logger.info("Rejected {} search results for the following reason: {}", entry.getCount(), entry.getElement());
            }
        }

        return new AcceptorResult(acceptedResults, reasonsForRejection);
    }

    protected boolean checkForNeededAttributesSuccessfullyMapped(Multiset<String> reasonsForRejection, SearchResultItem item) {
        boolean accepted = true;
        if (item.getTitle() == null) {
            logger.debug("Title could not be found or parsed");
            accepted = false;
        } else if (item.getIndexerGuid() == null) {
            logger.debug("GUID could not be found or parsed");
            accepted = false;
        } else if (item.getLink() == null) {
            logger.debug("Link could not be found or parsed");
            accepted = false;
        } else if ((item.getPubDate() == null && !item.getUsenetDate().isPresent())) {
            logger.debug("Neither pubdate nor usenet date could be found or parsed");
            accepted = false;
        }
        if (!accepted) {
            reasonsForRejection.add("Important data could not be mapped from the indexers returned response");
            return false;
        }

        return true;
    }

    protected boolean checkForCategory(SearchRequest searchRequest, Multiset<String> reasonsForRejection, SearchResultItem item) {
        if (item.getCategory().getIgnoreResultsFrom().meets(searchRequest.getSource())) {
            logger.debug(MARKER, "{} is in forbidden category", item.getTitle(), searchRequest.getCategory().getName());
            reasonsForRejection.add("In forbidden category");
            return false;
        }
        return true;
    }

    protected boolean checkForCategoryDisabledForIndexer(SearchRequest searchRequest, Multiset<String> reasonsForRejection, SearchResultItem item) {
        List<String> enabledCategories = item.getIndexer().getConfig().getEnabledCategories();
        if (!enabledCategories.isEmpty() && !enabledCategories.contains(item.getCategory().getName())) {
            logger.debug(MARKER, "{} is in category disabled for indexer", item.getTitle(), searchRequest.getCategory().getName());
            reasonsForRejection.add("In forbidden category");
            return false;
        }
        return true;
    }

    protected boolean checkForSize(SearchRequest searchRequest, Multiset<String> reasonsForRejection, SearchResultItem item) {
        boolean isApiAndLimitsShouldApply = item.getCategory().isApplySizeLimitsToApi() && searchRequest.getSource() == SearchSource.API;
        Integer minSize = searchRequest
                .getMinsize()
                .orElse(
                        isApiAndLimitsShouldApply && item.getCategory().getMinSizePreset().isPresent()
                                ? item.getCategory().getMinSizePreset().orElse(null)
                                : null
                );
        if (minSize != null && item.getSize() / (1024 * 1024) < minSize) {
            logger.debug(MARKER, "{} is smaller than {}", item.getTitle(), minSize);
            reasonsForRejection.add("Wrong size");
            return false;
        }
        Integer maxSize = searchRequest
                .getMaxsize()
                .orElse(
                        isApiAndLimitsShouldApply && item.getCategory().getMaxSizePreset().isPresent()
                                ? item.getCategory().getMaxSizePreset().orElse(null)
                                : null
                );
        if (maxSize != null && item.getSize() / (1024 * 1024) > maxSize) {
            logger.debug(MARKER, "{} is bigger than {}", item.getTitle(), maxSize);
            reasonsForRejection.add("Wrong size");
            return false;
        }
        return true;
    }

    protected boolean checkForAge(SearchRequest searchRequest, Multiset<String> reasonsForRejection, SearchResultItem item) {
        if (searchRequest.getMinage().isPresent() && item.getAgeInDays() < searchRequest.getMinage().get()) {
            logger.debug(MARKER, "{} is younger than {} days", item.getTitle(), searchRequest.getMinage().get());
            reasonsForRejection.add("Wrong age");
            return false;
        }
        if (searchRequest.getMaxage().isPresent() && item.getAgeInDays() > searchRequest.getMaxage().get()) {
            logger.debug(MARKER, "{} is older than {} days", item.getTitle(), searchRequest.getMaxage().get());
            reasonsForRejection.add("Wrong age");
            return false;
        }
        return true;
    }

    protected boolean checkForForbiddenGroup(Multiset<String> reasonsForRejection, SearchResultItem item) {
        if (item.getGroup().isPresent()) {
            if (configProvider.getBaseConfig().getSearching().getForbiddenGroups().stream().anyMatch(x -> item.getGroup().isPresent() && item.getGroup().get().contains(x))) {
                logger.debug(MARKER, "Found forbidden group {}", item.getGroup().get());
                reasonsForRejection.add("In forbidden group");
                return false;
            }
        }
        return true;
    }

    protected boolean checkForForbiddenPoster(Multiset<String> reasonsForRejection, SearchResultItem item) {
        if (item.getPoster().isPresent()) {
            if (configProvider.getBaseConfig().getSearching().getForbiddenPosters().stream().anyMatch(x -> item.getPoster().isPresent() && item.getPoster().get().contains(x))) {
                logger.debug(MARKER, "Found forbidden poster {}", item.getPoster().get());
                reasonsForRejection.add("In forbidden poster");
                return false;
            }
        }
        return true;
    }

    protected boolean checkRegexes(SearchResultItem item, Multiset<String> reasonsForRejection, String requiredRegex, String forbiddenRegex) {
        if (!Strings.isNullOrEmpty(requiredRegex) && !Pattern.compile(requiredRegex).matcher(item.getTitle().toLowerCase()).find()) {
            logger.debug(MARKER, "Did not find required regex in {}", item.getTitle());
            reasonsForRejection.add("Required regex doesn't match");
            return false;
        }
        if (!Strings.isNullOrEmpty(forbiddenRegex) && Pattern.compile(forbiddenRegex).matcher(item.getTitle().toLowerCase()).find()) {
            logger.debug(MARKER, "Found forbidden regex in {}", item.getTitle());
            reasonsForRejection.add("Forbidden regex matches");
            return false;
        }

        return true;
    }

    protected boolean checkRequiredWords(Multiset<String> reasonsForRejection, List<String> requiredWords, SearchResultItem item) {
        if (!requiredWords.isEmpty()) {
            List<String> titleWords = getTitleWords(item);

            for (String requiredWord : requiredWords) {
                if (requiredWord.contains(".") || requiredWord.contains("-")) {
                    if (item.getTitle().contains(requiredWord)) {
                        return true;
                    }
                } else {
                    if (titleWords.contains(requiredWord)) { //Words must match
                        return true;
                    }
                }
            }
            logger.debug(MARKER, "Did not found any of the required words in the title {}", item.getTitle());
            reasonsForRejection.add("No required word found");
            return false;
        }
        return true;
    }

    private List<String> getTitleWords(SearchResultItem item) {
        return titleWordCache.computeIfAbsent(item.getTitle(), s -> {
            List<String> titleWords = new ArrayList<>();
            Matcher matcher = TITLE_PATTERN.matcher(item.getTitle().toLowerCase());
            while (matcher.find()) {
                titleWords.add(matcher.group().toLowerCase());
            }
            return titleWords;
        });

    }

    protected boolean checkForForbiddenWords(IndexerConfig indexerConfig, Multiset<String> reasonsForRejection, List<String> forbiddenWords, SearchResultItem item) {
        for (String forbiddenWord : forbiddenWords) {
            if (forbiddenWord.contains("-") || forbiddenWord.contains(".") || indexerConfig.getHost().contains("nzbgeek")) {
                if (item.getTitle().toLowerCase().contains(forbiddenWord.toLowerCase())) {
                    reasonsForRejection.add("Forbidden word");
                    logger.debug(MARKER, "Found forbidden word {} in title {]", forbiddenWord, item.getTitle());
                    return false;
                }
            } else {
                List<String> titleWords = getTitleWords(item);
                Optional<String> found = titleWords.stream().filter(x -> x.equals(forbiddenWord)).findFirst(); //Title word must match excluded word to reject result, not just be contained
                if (found.isPresent()) {
                    logger.debug(MARKER, "Found forbidden word in title word {}", found.get());
                    reasonsForRejection.add("Forbidden word");
                    return false;
                }
            }
        }
        return true;
    }

    protected boolean checkForPassword(Multiset<String> reasonsForRejection, SearchResultItem item) {
        if (configProvider.getBaseConfig().getSearching().isIgnorePassworded() && item.isPassworded()) {
            reasonsForRejection.add("Ignore passworded");
            return false;
        }
        return true;
    }

    @Data
    @AllArgsConstructor
    public static class AcceptorResult {

        private List<SearchResultItem> acceptedResults;
        private Multiset<String> reasonsForRejection;

        public int getNumberOfRejectedResults() {
            return reasonsForRejection.entrySet().stream().mapToInt(Multiset.Entry::getCount).sum();
        }

    }

}