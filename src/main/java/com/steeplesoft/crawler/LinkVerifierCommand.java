package com.steeplesoft.crawler;

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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import edu.uci.ics.crawler4j.crawler.CrawlConfig;
import edu.uci.ics.crawler4j.crawler.CrawlController;
import edu.uci.ics.crawler4j.fetcher.PageFetcher;
import edu.uci.ics.crawler4j.robotstxt.RobotstxtConfig;
import edu.uci.ics.crawler4j.robotstxt.RobotstxtServer;
import edu.uci.ics.crawler4j.url.WebURL;
import picocli.CommandLine;

@CommandLine.Command(name = "link-verifier",
    description = "Validates that links generated on a remote site are resolvable on the local dev site.",
    showDefaultValues = true)
public class LinkVerifierCommand implements Callable<Integer>  {
    @CommandLine.Option(names = {"-w", "--walk"}, description = "Whether to walk the remote site. Once the site is walked, --link-file can be used to avoid excessive traffic.")
    private boolean walk = false;
    @CommandLine.Option(names = {"-r", "--remote"}, description = "The remote site to crawl")
    private String remote;
    @CommandLine.Option(names = {"-a", "--alias"}, description = "An alias the site may use (e.g., https://www.example.com -> https://example.com)")
    private String[] aliases;
    @CommandLine.Option(names = {"-c", "--check", "--verify"}, description = "Whether to verify a remote URL before verifying locally. This avoids false positives from broken links on the remote site.")
    private boolean verify = false;
    @CommandLine.Option(names = {"-f", "--link-file"}, description = "A file containing links to check")
    private String linkFile;
    @CommandLine.Option(names = {"-l", "--local"}, description = "The local site to check")
    private String local;
    @CommandLine.Option(names = {"-i", "--ignore"}, description = "Paths to ignore. Can be repeated for multiple paths.")
    private String[] ignore;
    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Display this help message")
    boolean usageHelpRequested;

    public static void main(String[] args) throws Exception {
        final CommandLine commandLine = new CommandLine(new LinkVerifierCommand());
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (usageHelpRequested) {
            CommandLine.usage(this, System.out, CommandLine.Help.Ansi.AUTO);
            return CommandLine.ExitCode.OK;
        }

        // Fix up remote, local, and aliases to the ending / is consistent and predictable
        remote = remote.endsWith("/") ? remote : remote + "/";
        local = local.endsWith("/") ? local : local + "/";
        for (int i = 0; i < aliases.length; i++) {
            aliases[i] = aliases[i].endsWith("/") ? aliases[i] : aliases[i] + "/";
        }


        if (walk) {
            crawlRemoteSite();
        }

        if (linkFile != null) {
            checkLinks();
        }

        return CommandLine.ExitCode.OK;
    }

    private void crawlRemoteSite() throws Exception {
        final SiteCrawler crawler = new SiteCrawler();
        crawler.setRemote(remote);
        crawler.setAliases(aliases);
        crawler.ignore(ignore);

        String crawlStorageFolder = "target/data/crawl/root";
        int numberOfCrawlers = 7;

        CrawlController controller = getCrawlController(crawlStorageFolder);

        controller.addSeed(remote);
        controller.start(() -> crawler, numberOfCrawlers);

        var links = crawler.getAllLinks();
        var fileName = linkFile != null ? linkFile : "links.txt";
        Files.write(Path.of(fileName),
            links.stream().map(WebURL::getURL).sorted().toList(),
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
                    URI uri = transformLink(link);
                    final HttpRequest remoteRequest = HttpRequest.newBuilder(new URI(link)).GET().build();

                    // Check the remote site first to make sure the link is valid, as sometimes the production site
                    // has bad/broken links.
                    if (!verify || isRemoteValid(client, remoteRequest)) {
                        final HttpRequest localRequest = HttpRequest.newBuilder(uri).GET().build();
                        final int statusCode = client.send(localRequest, HttpResponse.BodyHandlers.discarding()).statusCode();
                        final boolean valid = (statusCode >= 200 && statusCode <= 399);
                        if (!valid) {
                            notFound.incrementAndGet();
                            System.err.println("The URL " + link + " from the original site was not found in the local site: " + uri);
                        } else {
                            found.incrementAndGet();
                        }
                    }
                } catch (URISyntaxException | IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });

            System.out.println("Found " + found.get() + " good links and " + notFound.get() + " invalid links.");
         }

    }

    private static boolean isRemoteValid(HttpClient client, HttpRequest remoteRequest) throws IOException, InterruptedException {
        return client.send(remoteRequest, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    }

    private URI transformLink(String link) throws URISyntaxException {
        String transformed = link.replace(remote, local);
        String alias = Arrays.stream(aliases).filter(transformed::startsWith).findFirst().orElse(null);
        if (alias != null) {
            transformed = transformed.replace(alias, local);
        }
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
