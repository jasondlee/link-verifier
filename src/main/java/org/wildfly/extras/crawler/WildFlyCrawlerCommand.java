package org.wildfly.extras.crawler;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import edu.uci.ics.crawler4j.crawler.CrawlConfig;
import edu.uci.ics.crawler4j.crawler.CrawlController;
import edu.uci.ics.crawler4j.fetcher.PageFetcher;
import edu.uci.ics.crawler4j.robotstxt.RobotstxtConfig;
import edu.uci.ics.crawler4j.robotstxt.RobotstxtServer;
import edu.uci.ics.crawler4j.url.WebURL;
import picocli.CommandLine;

@CommandLine.Command(name = "crawl",
    description = "Validates that links are resolvable.",
    showDefaultValues = true)
public class WildFlyCrawlerCommand implements Callable<Integer>  {
    @CommandLine.Option(names = {"-w", "--walk"}, description = "Whether to walk the remote site. Once the site is walked, --link-file can be used to avoid excessive traffic.")
    private boolean walk = false;
    @CommandLine.Option(names = {"-r", "--remote"}, description = "The remote site to crawl")
    private String remote;
    @CommandLine.Option(names = {"-f", "--link-file"}, description = "A file containing links to check")
    private String linkFile;
    @CommandLine.Option(names = {"-l", "--local"}, description = "The local site to check")
    private String local;
    @CommandLine.Option(names = {"-i", "--ignore"}, description = "Paths to ignore")
    private String[] ignore;


    public static void main(String[] args) throws Exception {
        final CommandLine commandLine = new CommandLine(new WildFlyCrawlerCommand());
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (walk) {
            crawlRemoteSite();
        }

        if (linkFile != null) {
            checkLinks();
        }

        return 0;
    }

    private void crawlRemoteSite() throws Exception {
        final WildFlyCrawler crawler = new WildFlyCrawler();
        crawler.setRemote(remote);
        crawler.ignore(ignore);

        String crawlStorageFolder = "target/data/crawl/root";
        int numberOfCrawlers = 7;

        CrawlController controller = getCrawlController(crawlStorageFolder);

        controller.addSeed(remote);
        controller.start(() -> crawler, numberOfCrawlers);

        var links = crawler.getAllLinks();
        var fileName = linkFile != null ? linkFile : "links.txt";
        Files.write(Path.of(fileName),
            links.stream().map(WebURL::getURL).toList(),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void checkLinks() throws Exception {
        Path path = Path.of(linkFile);
        if (!Files.exists(path)) {
            throw new RuntimeException("File does not exist: " + linkFile);
        }
        try(HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofMillis(2000))
            //.executor(executor)
            .build()) {
            final AtomicInteger found = new AtomicInteger(0);
            final AtomicInteger notFound = new AtomicInteger(0);
            Files.readAllLines(path).forEach(link -> {
                try {
                    final HttpRequest request = HttpRequest.newBuilder(transformLink(link))
                        .GET()
                        .build();
                    final int statusCode = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                    final boolean valid = (statusCode >= 200 && statusCode <= 399);
                    if (!valid) {
                        notFound.incrementAndGet();
                        System.err.println("The URL " + link + " from the original site was not found in the local site.");
                    } else {
                        found.incrementAndGet();
                    }
                } catch (URISyntaxException | IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });

            System.out.println("Found " + found.get() + " good links and " + notFound.get() + " invalid links.");
         }

    }

    private URI transformLink(String link) throws URISyntaxException {
        String transformed = link.replace(remote, local);
        return new URI(transformed);
    }

    private CrawlController getCrawlController(String crawlStorageFolder) throws Exception {
        CrawlConfig config = new CrawlConfig();
        config.setCrawlStorageFolder(crawlStorageFolder);
//        config.setMaxDepthOfCrawling(5);
        config.setCleanupDelaySeconds(5);
        config.setThreadMonitoringDelaySeconds(5);
        config.setPolitenessDelay(5);

        // Instantiate the controller for this crawl.
        PageFetcher pageFetcher = new PageFetcher(config);
        RobotstxtConfig robotstxtConfig = new RobotstxtConfig();
        RobotstxtServer robotstxtServer = new RobotstxtServer(robotstxtConfig, pageFetcher);
        return new CrawlController(config, pageFetcher, robotstxtServer);
    }
}
