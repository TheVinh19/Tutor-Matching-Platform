package vn.giasu.ai.assistant;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import vn.giasu.ai.Clients;
import vn.giasu.ai.assistant.Retriever.Entry;
import vn.giasu.ai.assistant.Retriever.SourceType;

/** What the assistant may answer from: built-in rules (ai/policies.txt) and the FAQ published by customer care, refreshed every 5 minutes. */
@Component
class Knowledge {
    private static final Duration REFRESH = Duration.ofMinutes(5);

    private final Clients clients;
    private final List<Entry> policies;
    private volatile Retriever retriever;
    private volatile Instant loadedAt = Instant.EPOCH;

    Knowledge(Clients clients) {
        this.clients = clients;
        try (InputStream in = new ClassPathResource("ai/policies.txt").getInputStream()) {
            this.policies = parsePolicies(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Care being down keeps the last FAQ copy; with none yet, the rules alone still answer. */
    Retriever retriever() {
        if (retriever == null || Instant.now().isAfter(loadedAt.plus(REFRESH))) {
            synchronized (this) {
                if (retriever == null || Instant.now().isAfter(loadedAt.plus(REFRESH))) {
                    List<Clients.Faq> faqs = clients.faqs();
                    if (faqs != null || retriever == null) {
                        List<Entry> entries = new ArrayList<>(policies);
                        if (faqs != null) faqs.forEach(f -> entries.add(new Entry(SourceType.FAQ, f.id().toString(), f.question(), f.answer(), f.tags())));
                        retriever = new Retriever(entries);
                    }
                    loadedAt = Instant.now();
                }
            }
        }
        return retriever;
    }

    /** "## ID | question | tags" opens an entry, the following lines are its answer; lines starting with "# " are comments. */
    static List<Entry> parsePolicies(String text) {
        List<Entry> out = new ArrayList<>();
        String[] head = null;
        StringBuilder body = new StringBuilder();
        for (String line : (text + "\n## END").split("\\R")) {
            if (line.startsWith("## ")) {
                if (head != null) out.add(new Entry(SourceType.POLICY, head[0].strip(), head[1].strip(), body.toString().strip(), head[2].strip()));
                head = line.substring(3).split("\\|", 3);
                if (head.length < 3) head = null;
                body.setLength(0);
            } else if (!line.startsWith("# ") && head != null) {
                body.append(line).append('\n');
            }
        }
        return out;
    }
}
