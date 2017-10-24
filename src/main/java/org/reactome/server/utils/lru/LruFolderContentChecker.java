package org.reactome.server.utils.lru;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;


/**
 * This class is for check if the size of a specific folder
 * every X time. You must provide the path of the folder,
 * the maximum size, the threshold (how much space has to clean)
 * and the time (frequency of the checking).
 * <p>
 * Created by Oscar Forner on 22/03/14.
 */
@SuppressWarnings("unused")
public class LruFolderContentChecker extends Thread {

    //************************/
    //***   Constructors   ***/
    //************************/
    public LruFolderContentChecker(String pathDirectory, Long maxSize, Long threshold, Long time) {
        this.directory = new File(pathDirectory);
        //Use the path from the File because always returns the path without / at the end.
        this.pathDirectory = this.directory.getAbsolutePath();
        this.maxSize = maxSize;
        this.threshold = threshold;
        this.time = time;
        this.ttl = FileTime.from(0L, TimeUnit.MILLISECONDS);
        this.handlers = new LinkedList<>();
    }

    public LruFolderContentChecker(String pathDirectory, Long maxSize, Long threshold, Long time, Long ttl) {
        this.directory = new File(pathDirectory);
        //Use the path from the File because always returns the path without / at the end.
        this.pathDirectory = this.directory.getAbsolutePath();
        this.maxSize = maxSize;
        this.threshold = threshold;
        this.time = time;
        this.ttl = FileTime.from(ttl, TimeUnit.MILLISECONDS);
        this.handlers = new LinkedList<>();
    }

    public boolean addCheckerFileDeletedHandler(LruFolderContentCheckerFileDeletedHandler handler) {
        return this.handlers.add(handler);
    }

    //*************************/
    //***   Public method   ***/
    //*************************/
    @Override
    public void run() {
        Long currentSize;
        PriorityQueue<BasicFileAttributesAndPath> minHeap;
        //noinspection InfiniteLoopStatement
        try {
            log.info(LruFolderContentChecker.class.getSimpleName() + " started");
            while (active) {
                //Check the current size of the folder.
                currentSize = getFolderSize();
                if (currentSize != null) {
                    //This is used to force the min heap to that size. Then it avoids to increment the space.
                    int heapSize = this.directory.list().length;
                    //heapSize is zero when the directory does not have files.
                    if (heapSize != 0) {
                        //PriorityQueue of the structure to store the BasicFileAttributes and the Path, to be able to sort them using the
                        //lastAccessTime.
                        //This compare allows us to sort the elements by the lastAccessTime.
                        minHeap = new PriorityQueue<>(heapSize, Comparator.comparing(o -> o.getAttr().lastAccessTime()));
                        //Only check the content of the directory if the maximum size is smaller than the current size plus the threshold.
                        //Example: maxSize = 10Gb; currentSize = 8Gb; threshold = 1Gb;
                        //10 < (8 + 1) --> false
                        if (this.maxSize < (currentSize + this.threshold)) {
                            //Traverse all the
                            BasicFileAttributes file;
                            FileTime now = FileTime.from(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                            for (String node : this.directory.list()) {
                                file = Files.readAttributes(Paths.get(getPathFile(node)), BasicFileAttributes.class);

                                if (now.compareTo(FileTime.from(file.lastAccessTime().toMillis() + this.ttl.toMillis(), TimeUnit.MILLISECONDS)) == 1) {
                                    minHeap.add(new BasicFileAttributesAndPath(file, getPathFile(node)));
                                }
                            }
                            if (minHeap.size() > 0) {
                                while (this.maxSize < (currentSize + this.threshold)) {
                                    BasicFileAttributesAndPath attrAndPath = minHeap.poll();
                                    File toDelete = new File(attrAndPath.getPath());
                                    if (toDelete.isFile()) {
                                        FileUtils.forceDelete(toDelete);
                                        for (LruFolderContentCheckerFileDeletedHandler handler : this.handlers) {
                                            handler.onLruFolderContentCheckerFileDeleted(attrAndPath.getPath());
                                        }
                                    }
                                    currentSize = getFolderSize();
                                }
                            }
                        }
                    }
                } else {
                    log.error("Directory " + this.pathDirectory + " to check does not exist");
                }
                if(active) Thread.sleep(this.time);
            }
        } catch (InterruptedException e) {
            log.info(e.getMessage());
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    public void setLoggerName(String loggerName) {
        log = LoggerFactory.getLogger(loggerName);
    }

    @Override
    public void interrupt() {
        this.active = false;
        this.handlers.clear();
        super.interrupt();
        log.info(LruFolderContentChecker.class.getSimpleName() + " interrupted");
    }

    //***************************/
    //***   Private methods   ***/
    //***************************/
    private String getPathFile(String file) {
        return new StringBuilder().append(this.pathDirectory).append("/").append(file).toString();
    }

    private Long getFolderSize() {
        Long result = null;
        if (this.directory.isDirectory()) {
            result = 0L;
            for (String file : this.directory.list()) {
                try {
                    Path pathFile = Paths.get(getPathFile(file));
                    if (Files.isRegularFile(pathFile)) {
                        result += Files.size(Paths.get(getPathFile(file)));
                    }
                } catch (IOException e) {
                    log.error("Access error in the " + getPathFile(file) + " file.", e);
                }
            }
        }
        return result;
    }

    //******************************/
    //***   Private attributes   ***/
    //******************************/
    private String pathDirectory = null;
    private final File directory;
    private Long maxSize = null;
    private Long threshold = null;
    private Long time = null;
    private FileTime ttl = null;
    private boolean active = true;
    private static Logger log = LoggerFactory.getLogger("LruFolderContentChecker");
    private List<LruFolderContentCheckerFileDeletedHandler> handlers;


    //****************************/
    //***   Private subclass   ***/
    //****************************/
    private class BasicFileAttributesAndPath {
        private BasicFileAttributes attr = null;
        private String path = null;

        private BasicFileAttributesAndPath(BasicFileAttributes attr, String path) {
            this.attr = attr;
            this.path = path;
        }

        BasicFileAttributes getAttr() {
            return attr;
        }

        String getPath() {
            return path;
        }
    }

}
