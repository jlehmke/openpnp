package org.openpnp.spi;

import java.util.List;
import java.util.Map;
import java.util.Collections;

import org.openpnp.model.ProjectFile;
import org.openpnp.model.ProjectRecord;

/**
 * Abstraction for a "design database" that can store and retrieve PCB project
 * file artifacts (placement exports, board definitions, panel definitions).
 *
 * <p>Implementations may be standalone (e.g. a local folder) or combined with a
 * {@link PartDatabase} (e.g. PartDB, InvenTree). The interface is intentionally
 * minimal so that any file-capable backend can implement it.
 *
 * <p>A project is an opaque grouping identified by a string ID. Files within a
 * project are identified by their own string IDs.
 */
public interface ProjectStorage {

    /** Returns {@code true} if the storage backend is reachable and authenticated. */
    boolean isConnected();

    /**
     * Lists projects whose name matches the given filter.
     *
     * @param nameFilter  substring to match (empty string returns all projects)
     * @return list of matching projects, never {@code null}
     */
    List<ProjectRecord> listProjects(String nameFilter) throws Exception;

    /**
     * Returns all files attached to the given project.
     *
     * @param projectId  opaque project ID as returned by {@link #listProjects}
     */
    List<ProjectFile> getProjectFiles(String projectId) throws Exception;

    /**
     * Downloads the raw bytes of a project file.
     *
     * @param projectId  project that owns the file
     * @param fileId     opaque file ID as returned by {@link #getProjectFiles}
     */
    byte[] downloadFile(String projectId, String fileId) throws Exception;

    /**
     * Uploads or replaces a file in a project.
     *
     * <p>If a file with the same {@code fileType} already exists in the project it
     * is replaced; otherwise a new file is created.
     *
     * @param projectId  project to attach the file to
     * @param fileType   human-readable category label, e.g. {@code "OpenPnP Board"}
     * @param filename   suggested filename including extension
     * @param content    raw file bytes
     */
    void putFile(String projectId, String fileType, String filename, byte[] content)
            throws Exception;

    /**
     * Returns a map of placement designator → part name for the given project's BOM.
     *
     * <p>Keys are the reference designators as they appear in the placement file
     * (e.g. {@code "R1"}, {@code "U5"}). Values are the canonical part names that
     * should be used as OpenPnP Part IDs.
     *
     * <p>The default implementation returns an empty map. Backends that support BOMs
     * (e.g. PartDB) should override this method.
     */
    default Map<String, String> getDesignatorToPartName(String projectId) throws Exception {
        return Collections.emptyMap();
    }

    /**
     * Synchronises BOM mountnames and quantities for the given project.
     *
     * <p>For each part in {@code partToMountnames}:
     * <ul>
     *   <li>If the list is non-empty: update the BOM entry's mountnames and set
     *       quantity = list size.</li>
     *   <li>If the list is empty: delete the BOM entry (the part has no enabled
     *       placements in the board).</li>
     * </ul>
     * Parts in the BOM that are not mentioned in the map are left untouched.
     *
     * @param projectId        opaque project ID
     * @param partToMountnames map of OpenPnP part ID → list of enabled reference designators
     */
    default void syncBomEntries(String projectId,
            Map<String, List<String>> partToMountnames) throws Exception {
        // default no-op
    }
}
