package de.regelsuche.export;

/**
 * Imports a previously exported {@link ExportBundle} from JSON.
 *
 * <p>Counterpart to {@link TransformationExportService#exportJson} so that
 * external tools can round-trip the discovery output without having to
 * re-implement the schema.</p>
 */
public interface TransformationImportService {
    ExportBundle importJson(String json);
}
