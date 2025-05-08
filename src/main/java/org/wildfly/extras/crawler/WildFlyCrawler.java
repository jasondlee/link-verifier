package org.wildfly.extras.crawler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import edu.uci.ics.crawler4j.crawler.Page;
import edu.uci.ics.crawler4j.crawler.WebCrawler;
import edu.uci.ics.crawler4j.parser.HtmlParseData;
import edu.uci.ics.crawler4j.url.WebURL;

public class WildFlyCrawler extends WebCrawler {
    private final Set<WebURL> allLinks = new HashSet<>();
    private final List<String> ignore = new ArrayList<>();
    private String remote;

    private final static Pattern ACCEPT_FILTER = Pattern.compile(".*(\\.html|\\/)$");
    private final static Pattern IGNORE_FILTER = Pattern.compile(".*(\\.(jbig2|tiff|JPEG2000|gif|jpg"
        + "|png|mp3|mp4|zip|gz))$");

    @Override
    public boolean shouldVisit(Page referringPage, WebURL url) {
        String href = url.getURL().toLowerCase();
        boolean goodSuffix = ACCEPT_FILTER.matcher(href).matches() || href.endsWith("/");
        boolean goodPath = href.startsWith(remote) &&
            ignore.stream().noneMatch(path -> href.startsWith(remote + path));
        return goodSuffix && goodPath;
    }

    @Override
    public void visit(Page page) {
        String url = page.getWebURL().getURL();
        System.out.println("URL: " + url);

        if (page.getParseData() instanceof HtmlParseData htmlParseData) {
            htmlParseData.getOutgoingUrls().forEach(outgoingUrl -> {
                if (shouldVisit(page, outgoingUrl)) {
                    allLinks.add(outgoingUrl);
                }
            });
        }
        System.out.println("Total links found: " + allLinks.size());
    }

    public void setRemote(String remote) {
        this.remote = remote + (remote.endsWith("/") ? "" : "/");
    }

    public void ignore(String[] paths) {
        ignore.addAll(Arrays.asList(paths));
    }

    public Set<WebURL> getAllLinks() {
        return Collections.unmodifiableSet(allLinks);
    }
}
