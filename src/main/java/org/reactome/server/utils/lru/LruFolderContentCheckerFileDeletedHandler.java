package org.reactome.server.utils.lru;

/**
 * This interface is to help the process who controls
 * the LRU to know which files had been deleted.
 *
 * Created by Oscar Forner on 25/03/14.
 */
public interface LruFolderContentCheckerFileDeletedHandler {
    void onLruFolderContentCheckerFileDeleted(String fileName);
}
