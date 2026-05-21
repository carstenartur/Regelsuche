package de.regelsuche.persistence;

import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File-backed {@code RuleInventoryRepository} used by the killer-demo's
 * standard mode.
 *
 * <p>Extends {@link InMemoryRuleInventoryRepository} so reads stay in
 * memory (fast). Persists to {@code storagePath / inventory.json} on every
 * write — using the in-memory repo's existing {@code persistTo} /
 * {@code loadFrom} JSON format so the file is round-trip compatible with
 * the existing {@code inventory import/export} CLI flow.</p>
 */
public final class JsonFileRuleInventoryRepository extends InMemoryRuleInventoryRepository {

    public static final String STORAGE_FILE = "inventory.json";

    private final Path storageDirectory;
    private final Path file;

    public JsonFileRuleInventoryRepository(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
        this.file = storageDirectory.resolve(STORAGE_FILE);
        try {
            Files.createDirectories(storageDirectory);
            if (Files.exists(file)) {
                hydrate();
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to initialize JSON file inventory at " + storageDirectory, ex);
        }
    }

    public Path storagePath() {
        return storageDirectory;
    }

    public Path filePath() {
        return file;
    }

    private void hydrate() throws IOException {
        InMemoryRuleInventoryRepository loaded = InMemoryRuleInventoryRepository.loadFrom(file);
        for (ReusableRule rule : loaded.findAll()) {
            super.save(rule);
            if (!loaded.isEnabled(rule.id())) {
                super.setEnabled(rule.id(), false);
            }
            for (String tag : loaded.tagsOf(rule.id())) {
                super.addTag(rule.id(), tag);
            }
        }
    }

    private void persist() {
        try {
            persistTo(file);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to persist inventory to " + file, ex);
        }
    }

    @Override
    public synchronized void save(ReusableRule rule) {
        super.save(rule);
        persist();
    }

    @Override
    public void setEnabled(String ruleId, boolean enabled) {
        super.setEnabled(ruleId, enabled);
        persist();
    }

    @Override
    public void addTag(String ruleId, String tag) {
        super.addTag(ruleId, tag);
        persist();
    }

    @Override
    public void removeTag(String ruleId, String tag) {
        super.removeTag(ruleId, tag);
        persist();
    }
}
