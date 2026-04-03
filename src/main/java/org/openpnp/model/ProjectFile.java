package org.openpnp.model;

/**
 * Immutable value class representing a file attachment within a project,
 * as returned by a {@link org.openpnp.spi.ProjectStorage} backend.
 */
public final class ProjectFile {
    public final String id;
    /** Original filename including extension, e.g. {@code "design.pos"}. */
    public final String filename;
    /** Human-readable attachment type label from the backend, e.g. {@code "OpenPnP Board"}. */
    public final String typeName;
    /** URL used to download the file content. */
    public final String downloadUrl;

    public ProjectFile(String id, String filename, String typeName, String downloadUrl) {
        this.id = id;
        this.filename = filename != null ? filename : "";
        this.typeName = typeName != null ? typeName : "";
        this.downloadUrl = downloadUrl != null ? downloadUrl : "";
    }

    /** Returns a short description suitable for display in a list. */
    public String getTypeLabel() {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pos") || lower.endsWith(".csv")) {
            return "Placement file";
        }
        if (lower.endsWith(".board.xml")) {
            return "OpenPnP Board";
        }
        if (lower.endsWith(".panel.xml")) {
            return "OpenPnP Panel";
        }
        return typeName.isEmpty() ? "Attachment" : typeName;
    }

    @Override
    public String toString() {
        return filename;
    }
}
