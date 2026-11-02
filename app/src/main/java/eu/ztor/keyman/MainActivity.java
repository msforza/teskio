package eu.ztor.keyman;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;

public class MainActivity extends Activity {

    private EditText companyName;
    private EditText domain;
    private TextView result;
    private ProgressBar progress;

    private static final int MAX_CORPORATE_PAGES = 8;
    private static final int MAX_EXTERNAL_PAGES = 12;

    private static class SearchHit {
        String url;
        String text;
        SearchHit(String url, String text) {
            this.url = url;
            this.text = text;
        }
    }

    private static class Candidate {
        String name;
        String role;
        double decision;
        double relevance;
        double confidence;
        double score;
        LinkedHashSet<String> sources = new LinkedHashSet<>();

        Candidate(String name, String role, double decision, double relevance,
                  double confidence, String source) {
            this.name = name;
            this.role = role;
            this.decision = decision;
            this.relevance = relevance;
            this.confidence = confidence;
            if (source != null && !source.isEmpty()) sources.add(source);
            recalc();
        }

        void merge(Candidate other) {
            this.confidence = Math.min(0.98,
                    Math.max(this.confidence, other.confidence) +
                    (1.0 - Math.max(this.confidence, other.confidence)) * 0.18);
            this.decision = Math.max(this.decision, other.decision);
            this.relevance = Math.max(this.relevance, other.relevance);
            this.sources.addAll(other.sources);
            recalc();
        }

        void recalc() {
            this.score = score(decision, relevance, confidence);
        }

        String sourcesText() {
            StringBuilder b = new StringBuilder();
            int i = 0;
            for (String s : sources) {
                if (i++ > 0) b.append("\n      + ");
                b.append(s);
                if (i >= 3) break;
            }
            return b.toString();
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        companyName = findViewById(R.id.companyName);
        domain = findViewById(R.id.domain);
        result = findViewById(R.id.result);
        progress = findViewById(R.id.progress);
        Button analyze = findViewById(R.id.analyzeButton);

        analyze.setOnClickListener(v -> {
            String company = companyName.getText().toString().trim();
            String dom = domain.getText().toString().trim();

            if (company.isEmpty() || dom.isEmpty()) {
                Toast.makeText(this, "Inserisci azienda e dominio", Toast.LENGTH_SHORT).show();
                return;
            }

            progress.setVisibility(View.VISIBLE);
            result.setText("Ricerca multi-source in corso…");
            new Thread(() -> runPipeline(company, dom)).start();
        });
    }

    private void runPipeline(String company, String dom) {
        try {
            String normalized = normalizeBaseUrl(dom);
            String host = URI.create(normalized).getHost();
            ArrayList<Candidate> candidates = new ArrayList<>();

            // 1) Corporate website
            try {
                String home = get(normalized);
                LinkedHashSet<String> corporateUrls = new LinkedHashSet<>();
                corporateUrls.add(normalized);
                corporateUrls.addAll(discoverInterestingLinks(normalized, home));

                int count = 0;
                for (String url : corporateUrls) {
                    if (count++ >= MAX_CORPORATE_PAGES) break;
                    try {
                        String html = get(url);
                        candidates.addAll(extractPeople(
                                stripHtml(html), url, company, 0.93
                        ));
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            // 2) Free public web search: Bing RSS + DuckDuckGo HTML
            List<String> queries = buildQueries(company);
            LinkedHashMap<String, SearchHit> hits = new LinkedHashMap<>();

            for (String q : queries) {
                try {
                    for (SearchHit h : bingRssSearch(q)) {
                        if (isAllowedExternalUrl(h.url, host)) hits.put(h.url, h);
                    }
                } catch (Exception ignored) {}

                try {
                    for (SearchHit h : duckDuckGoSearch(q)) {
                        if (isAllowedExternalUrl(h.url, host)) hits.put(h.url, h);
                    }
                } catch (Exception ignored) {}
            }

            // 3) Extract from search snippets first (cheap/free)
            for (SearchHit h : hits.values()) {
                if (h.text != null && !h.text.isEmpty()) {
                    candidates.addAll(extractPeople(
                            stripHtml(h.text), h.url + " [search snippet]", company, 0.64
                    ));
                }
            }

            // 4) Fetch a limited number of public external pages
            int ext = 0;
            for (SearchHit h : hits.values()) {
                if (ext++ >= MAX_EXTERNAL_PAGES) break;
                try {
                    String html = get(h.url);
                    String text = stripHtml(html);
                    double conf = sourceConfidence(h.url, host);
                    candidates.addAll(extractPeople(text, h.url, company, conf));
                } catch (Exception ignored) {}
            }

            candidates = deduplicate(candidates);
            Collections.sort(candidates, (a,b) -> Double.compare(b.score, a.score));

            String output = render(company, dom, candidates, hits.size());

            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                result.setText(output);
            });

        } catch (Exception e) {
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                result.setText("Errore: " + e.getMessage());
            });
        }
    }

    private String normalizeBaseUrl(String dom) {
        String x = dom.trim();
        if (!x.startsWith("http://") && !x.startsWith("https://")) {
            x = "https://" + x;
        }
        return x;
    }

    private List<String> buildQueries(String company) {
        ArrayList<String> q = new ArrayList<>();
        q.add("\"" + company + "\" CEO OR \"Chief Executive Officer\" OR \"Managing Director\"");
        q.add("\"" + company + "\" \"Export Manager\" OR \"Export Director\" OR \"Head of Export\"");
        q.add("\"" + company + "\" \"International Sales\" OR \"Commercial Director\" OR \"Sales Director\"");
        q.add("\"" + company + "\" \"Business Development\" OR \"Supply Chain Director\" OR \"Logistics Director\"");
        q.add("\"" + company + "\" direttore commerciale OR responsabile export OR direttore export");
        return q;
    }

    private String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        c.setRequestProperty("Accept-Language", "it-IT,it;q=0.9,en;q=0.8");
        c.connect();

        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);

        InputStream in = c.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in));
        StringBuilder b = new StringBuilder();
        String line;
        int chars = 0;
        while ((line = r.readLine()) != null) {
            b.append(line).append('\n');
            chars += line.length();
            if (chars > 1_500_000) break;
        }
        r.close();
        return b.toString();
    }

    private List<SearchHit> bingRssSearch(String query) throws Exception {
        String url = "https://www.bing.com/search?format=rss&q=" +
                URLEncoder.encode(query, "UTF-8");
        String xml = get(url);
        ArrayList<SearchHit> out = new ArrayList<>();

        Pattern item = Pattern.compile("(?is)<item>(.*?)</item>");
        Matcher im = item.matcher(xml);
        while (im.find() && out.size() < 8) {
            String block = im.group(1);
            String link = xmlTag(block, "link");
            String title = xmlTag(block, "title");
            String desc = xmlTag(block, "description");
            if (link != null && !link.isEmpty()) {
                out.add(new SearchHit(htmlDecode(link), htmlDecode(title + " " + desc)));
            }
        }
        return out;
    }

    private List<SearchHit> duckDuckGoSearch(String query) throws Exception {
        String url = "https://html.duckduckgo.com/html/?q=" +
                URLEncoder.encode(query, "UTF-8");
        String html = get(url);
        ArrayList<SearchHit> out = new ArrayList<>();

        Pattern p = Pattern.compile(
                "(?is)<a[^>]+class=[\"'][^\"']*result__a[^\"']*[\"'][^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>"
        );
        Matcher m = p.matcher(html);

        while (m.find() && out.size() < 8) {
            String rawUrl = htmlDecode(m.group(1));
            String title = stripHtml(m.group(2));
            String target = decodeDdgUrl(rawUrl);
            if (target != null && target.startsWith("http")) {
                out.add(new SearchHit(target, title));
            }
        }
        return out;
    }

    private String decodeDdgUrl(String raw) {
        try {
            if (raw.startsWith("//")) raw = "https:" + raw;
            URI u = URI.create(raw);
            String q = u.getRawQuery();
            if (q != null) {
                for (String part : q.split("&")) {
                    int eq = part.indexOf('=');
                    if (eq > 0) {
                        String k = URLDecoder.decode(part.substring(0, eq), "UTF-8");
                        if ("uddg".equals(k)) {
                            return URLDecoder.decode(part.substring(eq + 1), "UTF-8");
                        }
                    }
                }
            }
            return raw;
        } catch (Exception e) {
            return raw;
        }
    }

    private String xmlTag(String block, String tag) {
        Matcher m = Pattern.compile("(?is)<" + tag + "[^>]*>(.*?)</" + tag + ">").matcher(block);
        return m.find() ? stripHtml(m.group(1)).trim() : "";
    }

    private String htmlDecode(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private boolean isAllowedExternalUrl(String url, String companyHost) {
        try {
            URI u = URI.create(url);
            String h = u.getHost();
            if (h == null) return false;
            String low = h.toLowerCase(Locale.ROOT);

            // Explicitly exclude LinkedIn and noisy social sites
            if (low.contains("linkedin.com") ||
                    low.contains("facebook.com") ||
                    low.contains("instagram.com") ||
                    low.contains("tiktok.com") ||
                    low.contains("x.com") ||
                    low.contains("twitter.com") ||
                    low.contains("youtube.com")) {
                return false;
            }

            // Avoid search engine pages themselves
            if (low.contains("bing.com") || low.contains("duckduckgo.com")) return false;

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private double sourceConfidence(String url, String companyHost) {
        try {
            String h = URI.create(url).getHost();
            if (h == null) return 0.62;
            h = h.toLowerCase(Locale.ROOT);
            String path = URI.create(url).getPath();
            path = path == null ? "" : path.toLowerCase(Locale.ROOT);

            if (companyHost != null && h.endsWith(companyHost.toLowerCase(Locale.ROOT))) return 0.93;

            if (h.contains("ansa.") || h.contains("reuters.") ||
                    h.contains("ilsole24ore.") || h.contains("forbes.") ||
                    h.contains("adnkronos.") || h.contains("ansa.it")) return 0.82;

            if (path.contains("press") || path.contains("news") ||
                    path.contains("comunicat") || path.contains("stampa")) return 0.80;

            if (h.contains("confindustria") || h.contains("camcom") ||
                    h.contains("unioncamere") || h.contains("ice.it") ||
                    h.contains("promositalia") || h.contains("associazione")) return 0.84;

            if (path.contains("event") || path.contains("speaker") ||
                    path.contains("relator") || path.contains("conference") ||
                    path.contains("fiera") || path.contains("exhibitor")) return 0.80;

            if (h.contains("crunchbase") || h.contains("zoominfo") ||
                    h.contains("rocketreach") || h.contains("contactout")) return 0.66;

            return 0.70;
        } catch (Exception e) {
            return 0.65;
        }
    }

    private List<String> discoverInterestingLinks(String base, String html) {
        ArrayList<String> out = new ArrayList<>();
        Pattern href = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = href.matcher(html);

        String[] keys = {
                "about","company","team","management","leadership","people",
                "chi-siamo","azienda","governance","export","sales","commercial",
                "news","press","stampa","management-team","organizzazione"
        };

        try {
            URI baseUri = URI.create(base);
            while (m.find() && out.size() < 24) {
                String raw = m.group(1);
                String low = raw.toLowerCase(Locale.ROOT);
                boolean interesting = false;
                for (String k : keys) {
                    if (low.contains(k)) { interesting = true; break; }
                }
                if (!interesting) continue;

                URI resolved = baseUri.resolve(raw);
                if (resolved.getHost() != null &&
                        resolved.getHost().equalsIgnoreCase(baseUri.getHost())) {
                    out.add(resolved.toString());
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html
                .replaceAll("(?is)<script.*?>.*?</script>", " ")
                .replaceAll("(?is)<style.*?>.*?</style>", " ")
                .replaceAll("(?is)<noscript.*?>.*?</noscript>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private ArrayList<Candidate> extractPeople(String text, String source,
                                               String company, double confidence) {
        ArrayList<Candidate> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        String[][] roles = {
                {"Chief Executive Officer","CEO","100","75"},
                {"Amministratore Delegato","CEO","100","75"},
                {"CEO","CEO","100","75"},
                {"Founder","Founder","100","75"},
                {"Fondatore","Founder","100","75"},
                {"Managing Director","Managing Director","95","80"},
                {"Direttore Generale","General Manager","90","78"},
                {"General Manager","General Manager","90","78"},
                {"Export Director","Export Director","85","100"},
                {"Direttore Export","Export Director","85","100"},
                {"Head of Export","Head of Export","80","100"},
                {"Responsabile Export","Export Manager","76","98"},
                {"Export Manager","Export Manager","72","96"},
                {"International Sales Director","International Sales Director","85","100"},
                {"International Sales Manager","International Sales Manager","72","95"},
                {"Commercial Director","Commercial Director","85","92"},
                {"Direttore Commerciale","Commercial Director","85","92"},
                {"Sales Director","Sales Director","80","88"},
                {"Business Development Director","Business Development Director","75","90"},
                {"Head of Business Development","Business Development Director","72","90"},
                {"Business Development Manager","Business Development Manager","65","86"},
                {"Strategy Director","Strategy Director","75","92"},
                {"Market Intelligence Manager","Market Intelligence Manager","60","95"},
                {"Supply Chain Director","Supply Chain Director","75","78"},
                {"Logistics Director","Logistics Director","70","72"},
                {"Direttore Logistica","Logistics Director","70","72"},
                {"Procurement Director","Procurement Director","70","65"}
        };

        Pattern namePattern = Pattern.compile(
                "\\b([A-ZÀ-ÖØ-Þ][a-zà-öø-ÿ'’-]{1,30}(?:\\s+[A-ZÀ-ÖØ-Þ][a-zà-öø-ÿ'’-]{1,30}){1,3})\\b"
        );

        String lower = text.toLowerCase(Locale.ROOT);

        for (String[] rr : roles) {
            String rawRole = rr[0];
            int index = lower.indexOf(rawRole.toLowerCase(Locale.ROOT));

            while (index >= 0) {
                int a = Math.max(0, index - 150);
                int b = Math.min(text.length(), index + rawRole.length() + 150);
                String window = text.substring(a, b);

                ArrayList<String> names = new ArrayList<>();
                Matcher nm = namePattern.matcher(window);
                while (nm.find()) {
                    String person = nm.group(1).trim();
                    if (isPlausibleName(person, company, rawRole)) names.add(person);
                }

                if (!names.isEmpty()) {
                    String best = nearestName(window, rawRole, names);
                    out.add(new Candidate(
                            best,
                            rr[1],
                            Double.parseDouble(rr[2]),
                            Double.parseDouble(rr[3]),
                            confidence,
                            source
                    ));
                }

                index = lower.indexOf(rawRole.toLowerCase(Locale.ROOT), index + rawRole.length());
            }
        }
        return out;
    }

    private String nearestName(String window, String role, List<String> names) {
        int r = window.toLowerCase(Locale.ROOT).indexOf(role.toLowerCase(Locale.ROOT));
        String best = names.get(0);
        int bestDist = Integer.MAX_VALUE;

        for (String n : names) {
            int p = window.indexOf(n);
            int d = Math.abs(p - r);
            if (d < bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return best;
    }

    private boolean isPlausibleName(String name, String company, String role) {
        String low = name.toLowerCase(Locale.ROOT);

        if (low.contains(company.toLowerCase(Locale.ROOT))) return false;
        if (low.contains(role.toLowerCase(Locale.ROOT))) return false;

        String[] bad = {
                "chief executive", "managing director", "general manager",
                "export manager", "export director", "sales director",
                "business development", "commercial director",
                "supply chain", "logistics director", "market intelligence",
                "international sales", "direttore commerciale",
                "responsabile export", "direttore export",
                "cookie policy", "privacy policy", "read more",
                "home page", "press release", "comunicato stampa"
        };

        for (String x : bad) if (low.contains(x)) return false;

        String[] parts = name.trim().split("\\s+");
        if (parts.length < 2 || parts.length > 4) return false;

        return true;
    }

    private ArrayList<Candidate> deduplicate(ArrayList<Candidate> input) {
        LinkedHashMap<String,Candidate> map = new LinkedHashMap<>();

        for (Candidate c : input) {
            String key = normalizePersonKey(c.name);
            if (key.length() < 5) continue;

            Candidate old = map.get(key);
            if (old == null) {
                map.put(key, c);
            } else {
                old.merge(c);
            }
        }
        return new ArrayList<>(map.values());
    }

    private String normalizePersonKey(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-zà-ÿ ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static double score(double decision, double relevance, double confidence) {
        double trigger = 60;
        double accessibility = 55;
        return round1(
                decision * 0.28 +
                relevance * 0.30 +
                (confidence * 100) * 0.24 +
                trigger * 0.10 +
                accessibility * 0.08
        );
    }

    private static double round1(double n) {
        return Math.round(n * 10.0) / 10.0;
    }

    private String recommendedAsset(String role) {
        String r = role.toLowerCase(Locale.ROOT);
        if (r.contains("export") || r.contains("international sales") ||
                r.contains("business development"))
            return "Competitor Pulse / Hot Zones Map";
        if (r.contains("supply chain") || r.contains("logistics") ||
                r.contains("procurement"))
            return "Flight Radar Live";
        if (r.contains("ceo") || r.contains("founder") ||
                r.contains("managing director") || r.contains("general manager"))
            return "Market Breach Simulator";
        return "Competitor Pulse";
    }

    private String render(String company, String dom,
                          ArrayList<Candidate> people, int webHits) {
        StringBuilder b = new StringBuilder();
        b.append(company).append("\n").append(dom).append("\n\n");
        b.append("FONTI: sito aziendale + web pubblico gratuito\n");
        b.append("Risultati web analizzati: ").append(webHits).append("\n");
        b.append("LinkedIn: escluso\n\n");

        if (people.isEmpty()) {
            b.append("Nessun key man sufficientemente identificato.\n\n")
             .append("Il motore ha cercato anche su news, fiere, associazioni, directory e pagine pubbliche indicizzate. ")
             .append("Alcuni siti o motori possono bloccare richieste automatiche.");
            return b.toString();
        }

        int n = Math.min(8, people.size());
        Candidate primary = people.get(0);

        double account = 0;
        for (int i=0; i<n; i++) account += people.get(i).score;
        account = round1(account / n);

        b.append("ACCOUNT SCORE: ").append(account).append("/100\n\n");
        b.append("PRIMARY TARGET\n")
         .append(primary.name).append("\n")
         .append(primary.role).append("\n")
         .append("Key Man Score: ").append(primary.score).append("\n")
         .append("Confidence: ").append(Math.round(primary.confidence * 100)).append("%\n")
         .append("Recommended Asset: ").append(recommendedAsset(primary.role))
         .append("\n\nTOP KEY PEOPLE\n\n");

        for (int i=0; i<n; i++) {
            Candidate c = people.get(i);
            b.append(i+1).append(". ").append(c.name).append("\n")
             .append("   ").append(c.role).append("\n")
             .append("   Score: ").append(c.score).append("\n")
             .append("   Confidence: ").append(Math.round(c.confidence*100)).append("%\n")
             .append("   Source: ").append(c.sourcesText()).append("\n\n");
        }

        b.append("v0.3.1 Multi-Source — fonti pubbliche gratuite; LinkedIn escluso.");
        return b.toString();
    }
}
