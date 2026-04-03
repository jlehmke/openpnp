package org.openpnp.model;

import java.util.Collections;
import java.util.List;

/**
 * Immutable value class representing a project entry returned by a
 * {@link org.openpnp.spi.ProjectStorage} backend.
 */
public final class ProjectRecord {
    public final String id;
    public final String name;
    public final String description;
    /** Files already known at list time; may be empty if not fetched yet. */
    public final List<ProjectFile> files;

    public ProjectRecord(String id, String name, String description, List<ProjectFile> files) {
        this.id = id;
        this.name = name != null ? name : "";
        this.description = description != null ? description : "";
        this.files = files != null ? Collections.unmodifiableList(files) : Collections.emptyList();
    }

    @Override
    public String toString() {
        return name;
    }
}
