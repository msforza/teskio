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

    private static class Candidate {
        String name;
        String role;
        double decision;
        double relevance;
        double confidence;
        double score;
        String source;

        Candidate(String name, String role, double decision, double relevance, double confidence, String source) {
            this.name = name;
            this.role = role;
            this.decision = decision;
            this.relevance = relevance;
            this.confidence = confidence;
            this.source = source;
            this.score = score(decision, relevance, confidence);
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
                Toast.makeText(this, "Insert company and domain", Toast.LENGTH_SHORT).show();
                return;
            }

            progress.setVisibility(View.VISIBLE);
            result.setText("");
            new Thread(() -> runPipeline(company, dom)).start();
        });
    }

    private void runPipeline(String company, String dom) {
        try {
            String normalized = dom.startsWith("http://") || dom.startsWith("https://")
                    ? dom : "https://" + dom;

            LinkedHashSet<String> urls = new LinkedHashSet<>();
            urls.add(normalized);

            String home = get(normalized);
            urls.addAll(discoverInterestingLinks(normalized, home));

            ArrayList<Candidate> candidates = new ArrayList<>();

            int count = 0;
            for (String url : urls) {
                if (count++ >= 8) break;
                try {
                    String html = get(url);
                    String text = stripHtml(html);
                    candidates.addAll(extractPeople(text, url, company));
                } catch (Exception ignored) {}
            }

            candidates = deduplicate(candidates);
            Collections.sort(candidates, (a,b) -> Double.compare(b.score, a.score));

            String output = render(company, dom, candidates);

            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                result.setText(output);
            });

        } catch (Exception e) {
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                result.setText("Error: " + e.getMessage());
            });
        }
    }

    private String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ZTOR-KeyMan-Android/0.3");
        c.connect();

        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);

        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line).append('\n');
        r.close();
        return b.toString();
    }

    private List<String> discoverInterestingLinks(String base, String html) {
        ArrayList<String> out = new ArrayList<>();
        Pattern href = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = href.matcher(html);

        String[] keys = {"about","company","team","management","leadership","people",
                "chi-siamo","azienda","governance","export","sales","commercial"};

        try {
            URI baseUri = URI.create(base);
            while (m.find() && out.size() < 20) {
                String raw = m.group(1);
                String low = raw.toLowerCase(Locale.ROOT);
                boolean interesting = false;
                for (String k : keys) if (low.contains(k)) { interesting = true; break; }
                if (!interesting) continue;

                URI resolved = baseUri.resolve(raw);
                if (resolved.getHost() != null && resolved.getHost().equalsIgnoreCase(baseUri.getHost())) {
                    out.add(resolved.toString());
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private String stripHtml(String html) {
        return html
                .replaceAll("(?is)<script.*?>.*?</script>", " ")
                .replaceAll("(?is)<style.*?>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ");
    }

    private ArrayList<Candidate> extractPeople(String text, String source, String company) {
        ArrayList<Candidate> out = new ArrayList<>();

        String[][] roles = {
                {"Chief Executive Officer","CEO","100","75"},
                {"CEO","CEO","100","75"},
                {"Founder","Founder","100","75"},
                {"Managing Director","Managing Director","95","80"},
                {"General Manager","General Manager","90","78"},
                {"Export Director","Export Director","85","100"},
                {"Head of Export","Head of Export","80","100"},
                {"Export Manager","Export Manager","72","96"},
                {"International Sales Director","International Sales Director","85","100"},
                {"International Sales Manager","International Sales Manager","72","95"},
                {"Commercial Director","Commercial Director","85","92"},
                {"Sales Director","Sales Director","80","88"},
                {"Business Development Director","Business Development Director","75","90"},
                {"Head of Business Development","Business Development Director","72","90"},
                {"Business Development Manager","Business Development Manager","65","86"},
                {"Strategy Director","Strategy Director","75","92"},
                {"Market Intelligence Manager","Market Intelligence Manager","60","95"},
                {"Supply Chain Director","Supply Chain Director","75","78"},
                {"Logistics Director","Logistics Director","70","72"},
                {"Procurement Director","Procurement Director","70","65"}
        };

        Pattern namePattern = Pattern.compile(
                "\\b([A-ZÀ-ÖØ-Þ][a-zà-öø-ÿ'’-]+(?:\\s+[A-ZÀ-ÖØ-Þ][a-zà-öø-ÿ'’-]+){1,3})\\b");

        String lower = text.toLowerCase(Locale.ROOT);

        for (String[] rr : roles) {
            String raw = rr[0];
            int index = lower.indexOf(raw.toLowerCase(Locale.ROOT));
            while (index >= 0) {
                int a = Math.max(0, index - 110);
                int b = Math.min(text.length(), index + raw.length() + 110);
                String window = text.substring(a,b);

                Matcher nm = namePattern.matcher(window);
                while (nm.find()) {
                    String person = nm.group(1);
                    if (!person.toLowerCase(Locale.ROOT).contains(company.toLowerCase(Locale.ROOT))) {
                        out.add(new Candidate(
                                person,
                                rr[1],
                                Double.parseDouble(rr[2]),
                                Double.parseDouble(rr[3]),
                                0.82,
                                source
                        ));
                        break;
                    }
                }
                index = lower.indexOf(raw.toLowerCase(Locale.ROOT), index + raw.length());
            }
        }
        return out;
    }

    private ArrayList<Candidate> deduplicate(ArrayList<Candidate> input) {
        LinkedHashMap<String,Candidate> map = new LinkedHashMap<>();
        for (Candidate c : input) {
            String key = c.name.toLowerCase(Locale.ROOT).replaceAll("[^a-zà-ÿ ]","").trim();
            Candidate old = map.get(key);
            if (old == null || c.score > old.score) map.put(key, c);
        }
        return new ArrayList<>(map.values());
    }

    private static double score(double decision, double relevance, double confidence) {
        double trigger = 60;
        double accessibility = 50;
        return round1(
                decision * 0.28 +
                relevance * 0.30 +
                (confidence * 100) * 0.22 +
                trigger * 0.12 +
                accessibility * 0.08
        );
    }

    private static double round1(double n) {
        return Math.round(n * 10.0) / 10.0;
    }

    private String recommendedAsset(String role) {
        String r = role.toLowerCase(Locale.ROOT);
        if (r.contains("export") || r.contains("international sales") || r.contains("business development"))
            return "Competitor Pulse / Hot Zones Map";
        if (r.contains("supply chain") || r.contains("logistics") || r.contains("procurement"))
            return "Flight Radar Live";
        if (r.contains("ceo") || r.contains("founder") || r.contains("managing director"))
            return "Market Breach Simulator";
        return "Competitor Pulse";
    }

    private String render(String company, String dom, ArrayList<Candidate> people) {
        StringBuilder b = new StringBuilder();
        b.append(company).append("\n").append(dom).append("\n\n");

        if (people.isEmpty()) {
            b.append("No high-confidence key people discovered from the public company site.\n\n")
             .append("Try a corporate site containing management/team pages.");
            return b.toString();
        }

        int n = Math.min(5, people.size());
        Candidate primary = people.get(0);

        double account = 0;
        for (int i=0; i<n; i++) account += people.get(i).score;
        account = round1(account/n);

        b.append("ACCOUNT SCORE: ").append(account).append("/100\n\n");
        b.append("PRIMARY TARGET\n")
         .append(primary.name).append("\n")
         .append(primary.role).append("\n")
         .append("Key Man Score: ").append(primary.score).append("\n")
         .append("Recommended Asset: ").append(recommendedAsset(primary.role))
         .append("\n\nTOP KEY PEOPLE\n\n");

        for (int i=0; i<n; i++) {
            Candidate c = people.get(i);
            b.append(i+1).append(". ").append(c.name).append("\n")
             .append("   ").append(c.role).append("\n")
             .append("   Score: ").append(c.score).append("\n")
             .append("   Confidence: ").append(Math.round(c.confidence*100)).append("%\n")
             .append("   Source: ").append(c.source).append("\n\n");
        }

        b.append("v0.3 Android performs public corporate-site discovery locally.");
        return b.toString();
    }
}
