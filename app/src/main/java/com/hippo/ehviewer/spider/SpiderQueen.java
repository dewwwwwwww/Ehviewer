/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.spider;

import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.collection.LongSparseArray;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhRequestBuilder;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.PreviewSet;
import com.hippo.ehviewer.client.exception.Image509Exception;
import com.hippo.ehviewer.client.exception.ParseException;
import com.hippo.ehviewer.client.parser.GalleryDetailParser;
import com.hippo.ehviewer.client.parser.GalleryMultiPageViewerPTokenParser;
import com.hippo.ehviewer.client.parser.GalleryPageApiParser;
import com.hippo.ehviewer.client.parser.GalleryPageParser;
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser;
import com.hippo.image.Image;
import com.hippo.unifile.UniFile;
import com.hippo.util.ExceptionUtils;
import com.hippo.yorozuya.MathUtils;
import com.hippo.yorozuya.OSUtils;
import com.hippo.yorozuya.StringUtils;
import com.hippo.yorozuya.thread.PriorityThread;
import com.hippo.yorozuya.thread.PriorityThreadFactory;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import eu.kanade.tachiyomi.ui.reader.loader.PageLoader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class SpiderQueen implements Runnable {

    public static final int MODE_READ = 0;
    public static final int MODE_DOWNLOAD = 1;
    public static final int STATE_NONE = 0;
    public static final int STATE_DOWNLOADING = 1;
    public static final int STATE_FINISHED = 2;
    public static final int STATE_FAILED = 3;
    public static final int DECODE_THREAD_NUM = 1;
    public static final String SPIDER_INFO_FILENAME = ".ehviewer";
    private static final String TAG = SpiderQueen.class.getSimpleName();
    private static final AtomicInteger sIdGenerator = new AtomicInteger();
    private static final boolean DEBUG_LOG = true;
    private static final boolean DEBUG_PTOKEN = true;
    private static final String[] URL_509_SUFFIX_ARRAY = {
            "/509.gif",
            "/509s.gif"
    };
    private static final LongSparseArray<SpiderQueen> sQueenMap = new LongSparseArray<>();
    @NonNull
    private final OkHttpClient mHttpClient;
    @NonNull
    private final GalleryInfo mGalleryInfo;
    @NonNull
    private final SpiderDen mSpiderDen;
    private final Object mQueenLock = new Object();
    private final Thread[] mDecodeThreadArray = new Thread[DECODE_THREAD_NUM];
    private final int[] mDecodeIndexArray = new int[DECODE_THREAD_NUM];
    private final Queue<Integer> mDecodeRequestQueue = new LinkedList<>();
    private final Object mWorkerLock = new Object();
    private final Object mPTokenLock = new Object();
    private final AtomicReference<SpiderInfo> mSpiderInfo = new AtomicReference<>();
    private final Queue<Integer> mRequestPTokenQueue = new ConcurrentLinkedQueue<>();
    private final Object mPageStateLock = new Object();
    // Store request page. The index may be invalid
    private final Queue<Integer> mRequestPageQueue = new LinkedList<>();
    // Store preload page. The index may be invalid
    private final Queue<Integer> mRequestPageQueue2 = new LinkedList<>();
    // Store force request page. The index may be invalid
    private final Queue<Integer> mForceRequestPageQueue = new LinkedList<>();
    private final AtomicInteger mDownloadedPages = new AtomicInteger(0);
    private final AtomicInteger mFinishedPages = new AtomicInteger(0);
    private final Object showKeyLock = new Object();
    // Store page error
    private final ConcurrentHashMap<Integer, String> mPageErrorMap = new ConcurrentHashMap<>();
    // Store page download percent
    private final ConcurrentHashMap<Integer, Float> mPagePercentMap = new ConcurrentHashMap<>();
    private final List<OnSpiderListener> mSpiderListeners = new ArrayList<>();
    private final int mWorkerMaxCount;
    private final int mPreloadNumber;
    private final int mDownloadDelay;
    private final AtomicReference<String> showKey = new AtomicReference<>();
    private int mReadReference = 0;
    private int mDownloadReference = 0;
    // It mQueenThread is null, failed or stopped
    @Nullable
    private volatile Thread mQueenThread;
    private ThreadPoolExecutor mWorkerPoolExecutor;
    private int mWorkerCount;
    private volatile int[] mPageStateArray;
    // For download, when it go to mPageStateArray.size(), done
    private volatile int mDownloadPage = -1;

    private boolean mStoped = false;

    private SpiderQueen(@NonNull GalleryInfo galleryInfo) {
        mHttpClient = EhApplication.getOkHttpClient();
        mGalleryInfo = galleryInfo;
        mSpiderDen = new SpiderDen(mGalleryInfo);

        mWorkerMaxCount = MathUtils.clamp(Settings.getMultiThreadDownload(), 1, 10);
        mPreloadNumber = MathUtils.clamp(Settings.getPreloadImage(), 0, 100);

        for (int i = 0; i < DECODE_THREAD_NUM; i++) {
            mDecodeIndexArray[i] = -1;
        }

        mWorkerPoolExecutor = new ThreadPoolExecutor(mWorkerMaxCount, mWorkerMaxCount,
                0, TimeUnit.SECONDS, new LinkedBlockingDeque<>(),
                new PriorityThreadFactory(SpiderWorker.class.getSimpleName(), Process.THREAD_PRIORITY_BACKGROUND));
        mDownloadDelay = Settings.getDownloadDelay();
    }

    @UiThread
    public static SpiderQueen obtainSpiderQueen(@NonNull GalleryInfo galleryInfo, @Mode int mode) {
        OSUtils.checkMainLoop();

        SpiderQueen queen = sQueenMap.get(galleryInfo.getGid());
        if (queen == null) {
            queen = new SpiderQueen(galleryInfo);
            sQueenMap.put(galleryInfo.getGid(), queen);
            // Set mode
            queen.setMode(mode);
            queen.start();
        } else {
            // Set mode
            queen.setMode(mode);
        }
        return queen;
    }

    @UiThread
    public static void releaseSpiderQueen(@NonNull SpiderQueen queen, @Mode int mode) {
        OSUtils.checkMainLoop();

        // Clear mode
        queen.clearMode(mode);

        if (queen.mReadReference == 0 && queen.mDownloadReference == 0) {
            // Stop and remove if there is no reference
            queen.stop();
            sQueenMap.remove(queen.mGalleryInfo.getGid());
        }
    }

    public static boolean contain(int[] array, int value) {
        for (int v : array) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    public void addOnSpiderListener(OnSpiderListener listener) {
        synchronized (mSpiderListeners) {
            mSpiderListeners.add(listener);
        }
    }

    public void removeOnSpiderListener(OnSpiderListener listener) {
        synchronized (mSpiderListeners) {
            mSpiderListeners.remove(listener);
        }
    }

    private void notifyGetPages(int pages) {
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onGetPages(pages);
            }
        }
    }

    private void notifyGet509(int index) {
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onGet509(index);
            }
        }
    }

    private void notifyPageDownload(int index, long contentLength, long receivedSize, int bytesRead) {
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onPageDownload(index, contentLength, receivedSize, bytesRead);
            }
        }
    }

    private void notifyPageSuccess(int index) {
        int size = -1;
        int[] temp = mPageStateArray;
        if (temp != null) {
            size = temp.length;
        }
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onPageSuccess(index, mFinishedPages.get(), mDownloadedPages.get(), size);
            }
        }
    }

    private void notifyPageFailure(int index, String error) {
        int size = -1;
        int[] temp = mPageStateArray;
        if (temp != null) {
            size = temp.length;
        }
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onPageFailure(index, error, mFinishedPages.get(), mDownloadedPages.get(), size);
            }
        }
    }

    private void notifyFinish() {
        int size = -1;
        int[] temp = mPageStateArray;
        if (temp != null) {
            size = temp.length;
        }
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onFinish(mFinishedPages.get(), mDownloadedPages.get(), size);
            }
        }
    }

    private void notifyGetImageSuccess(int index, Image image) {
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onGetImageSuccess(index, image);
            }
        }
    }

    private void notifyGetImageFailure(int index, String error) {
        if (error == null) {
            error = GetText.getString(R.string.error_unknown);
        }
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onGetImageFailure(index, error);
            }
        }
    }

    private void updateMode() {
        int mode;
        if (mDownloadReference > 0) {
            mode = MODE_DOWNLOAD;
        } else {
            mode = MODE_READ;
        }

        mSpiderDen.setMode(mode);

        // Update download page
        boolean intoDownloadMode = false;
        synchronized (mRequestPageQueue) {
            if (mode == MODE_DOWNLOAD) {
                if (mDownloadPage < 0) {
                    mDownloadPage = 0;
                    intoDownloadMode = true;
                }
            } else {
                mDownloadPage = -1;
            }
        }

        if (intoDownloadMode && mPageStateArray != null) {
            // Clear download state
            synchronized (mPageStateLock) {
                int[] temp = mPageStateArray;
                for (int i = 0, n = temp.length; i < n; i++) {
                    int oldState = temp[i];
                    if (STATE_DOWNLOADING != oldState) {
                        temp[i] = STATE_NONE;
                    }
                }
                mDownloadedPages.lazySet(0);
                mFinishedPages.lazySet(0);
                mPageErrorMap.clear();
                mPagePercentMap.clear();
            }
            // Ensure download workers
            ensureWorkers();
        }
    }

    private void setMode(@Mode int mode) {
        switch (mode) {
            case MODE_READ -> mReadReference++;
            case MODE_DOWNLOAD -> mDownloadReference++;
        }

        if (mDownloadReference > 1) {
            throw new IllegalStateException("mDownloadReference can't more than 0");
        }

        updateMode();
    }

    private void clearMode(@Mode int mode) {
        switch (mode) {
            case MODE_READ -> mReadReference--;
            case MODE_DOWNLOAD -> mDownloadReference--;
        }

        if (mReadReference < 0 || mDownloadReference < 0) {
            throw new IllegalStateException("Mode reference < 0");
        }

        updateMode();
    }

    private void start() {
        Thread queenThread = new PriorityThread(this, TAG + '-' + sIdGenerator.incrementAndGet(),
                Process.THREAD_PRIORITY_BACKGROUND);
        mQueenThread = queenThread;
        queenThread.start();
    }

    private void stop() {
        mStoped = true;
        synchronized (mQueenLock) {
            mQueenLock.notifyAll();
        }
        synchronized (mDecodeRequestQueue) {
            mDecodeRequestQueue.notifyAll();
        }
        synchronized (mWorkerLock) {
            mWorkerLock.notifyAll();
        }
    }

    public int size() {
        if (mQueenThread == null) {
            return PageLoader.STATE_ERROR;
        } else if (mPageStateArray == null) {
            return PageLoader.STATE_WAIT;
        } else {
            return mPageStateArray.length;
        }
    }

    public String getError() {
        if (mQueenThread == null) {
            return "Error";
        } else {
            return null;
        }
    }

    public Object forceRequest(int index) {
        return request(index, true, true, false);
    }

    public Object request(int index, boolean addNeighbor) {
        return request(index, true, false, addNeighbor);
    }

    private int getPageState(int index) {
        synchronized (mPageStateLock) {
            if (mPageStateArray != null && index >= 0 && index < mPageStateArray.length) {
                return mPageStateArray[index];
            } else {
                return STATE_NONE;
            }
        }
    }

    private void tryToEnsureWorkers() {
        boolean startWorkers = false;
        synchronized (mRequestPageQueue) {
            if (mPageStateArray != null &&
                    (!mForceRequestPageQueue.isEmpty() ||
                            !mRequestPageQueue.isEmpty() ||
                            !mRequestPageQueue2.isEmpty() ||
                            mDownloadPage >= 0 && mDownloadPage < mPageStateArray.length)) {
                startWorkers = true;
            }
        }

        if (startWorkers) {
            ensureWorkers();
        }
    }

    public void cancelRequest(int index) {
        if (mQueenThread == null) {
            return;
        }

        synchronized (mRequestPageQueue) {
            mRequestPageQueue.remove(index);
        }
        synchronized (mDecodeRequestQueue) {
            mDecodeRequestQueue.remove(index);
        }
    }

    public void preloadPages(@NonNull List<Integer> pages) {
        if (mQueenThread == null) {
            return;
        }

        synchronized (mRequestPageQueue) {
            pages.removeAll(mRequestPageQueue);
            pages.removeAll(mRequestPageQueue2);
            pages.removeAll(mDecodeRequestQueue);
            mRequestPageQueue2.addAll(pages);
        }
    }

    /**
     * @return String for error<br>
     * Float for download percent<br>
     * null for wait
     */
    private Object request(int index, boolean ignoreError, boolean force, boolean addNeighbor) {
        if (mQueenThread == null) {
            return null;
        }

        // Get page state
        int state = getPageState(index);

        // Fix state for force
        if ((force && (state == STATE_FINISHED || state == STATE_FAILED)) ||
                (ignoreError && state == STATE_FAILED)) {
            // Update state to none at once
            updatePageState(index, STATE_NONE);
            state = STATE_NONE;
        }

        // Add to request
        synchronized (mRequestPageQueue) {
            if (state == STATE_NONE) {
                if (force) {
                    mForceRequestPageQueue.add(index);
                } else {
                    mRequestPageQueue.add(index);
                }
            }

            // Add next some pages to request queue
            if (addNeighbor) {
                mRequestPageQueue2.clear();
                int[] pageStateArray = mPageStateArray;
                int size;
                if (pageStateArray != null) {
                    size = pageStateArray.length;
                } else {
                    size = Integer.MAX_VALUE;
                }
                for (int i = index + 1, n = index + 1 + mPreloadNumber; i < n && i < size; i++) {
                    if (STATE_NONE == getPageState(i)) {
                        mRequestPageQueue2.add(i);
                    }
                }
            }
        }

        Object result;

        switch (state) {
            case STATE_NONE -> result = null;
            case STATE_DOWNLOADING -> result = mPagePercentMap.get(index);
            case STATE_FAILED -> {
                String error = mPageErrorMap.get(index);
                if (error == null) {
                    error = GetText.getString(R.string.error_unknown);
                }
                result = error;
            }
            case STATE_FINISHED -> {
                synchronized (mDecodeRequestQueue) {
                    if (!contain(mDecodeIndexArray, index) && !mDecodeRequestQueue.contains(index)) {
                        mDecodeRequestQueue.add(index);
                        mDecodeRequestQueue.notifyAll();
                    }
                }
                result = null;
            }
            default -> throw new IllegalStateException("Unexpected value: " + state);
        }

        tryToEnsureWorkers();

        return result;
    }

    private void ensureWorkers() {
        synchronized (mWorkerLock) {
            if (null == mWorkerPoolExecutor) {
                Log.e(TAG, "Try to start worker after stopped");
                return;
            }

            for (; mWorkerCount < mWorkerMaxCount; mWorkerCount++) {
                mWorkerPoolExecutor.execute(new SpiderWorker());
            }
        }
    }

    public boolean save(int index, @NonNull UniFile file) {
        int state = getPageState(index);
        if (STATE_FINISHED != state) {
            return false;
        }

        return mSpiderDen.saveToUniFile(index, file);
    }

    @Nullable
    public UniFile save(int index, @NonNull UniFile dir, @NonNull String filename) {
        int state = getPageState(index);
        if (STATE_FINISHED != state) {
            return null;
        }

        var ext = mSpiderDen.getExtension(index);
        UniFile dst = dir.subFile(null != ext ? filename + "." + ext : filename);
        if (null == dst) {
            return null;
        }
        if (!mSpiderDen.saveToUniFile(index, dst)) return null;
        return dst;
    }


    @Nullable
    public String getExtension(int index) {
        int state = getPageState(index);
        if (STATE_FINISHED != state) {
            return null;
        }

        return mSpiderDen.getExtension(index);
    }

    // Mark as suspend fun when kotlinize, need IO
    public static int getStartPage(long gid) {
        var queen = sQueenMap.get(gid);
        SpiderInfo spiderInfo = null;

        // Fast Path: read existing queen
        if (queen != null) {
            spiderInfo = queen.mSpiderInfo.get();
        }

        // Slow path, read diskcache
        if (spiderInfo == null) {
            spiderInfo = SpiderInfoUtilsKt.readFromCache(gid);
        }

        if (spiderInfo == null) return 0;
        return spiderInfo.getStartPage();
    }

    public int getStartPage() {
        SpiderInfo spiderInfo = mSpiderInfo.get();
        if (spiderInfo == null) spiderInfo = readSpiderInfoFromLocal();
        if (spiderInfo == null) return 0;
        return spiderInfo.getStartPage();
    }

    public void putStartPage(int page) {
        final SpiderInfo spiderInfo = mSpiderInfo.get();
        if (spiderInfo != null) {
            spiderInfo.setStartPage(page);
        }
    }

    private synchronized SpiderInfo readSpiderInfoFromLocal() {
        SpiderInfo spiderInfo = mSpiderInfo.get();
        if (spiderInfo != null) {
            return spiderInfo;
        }

        // Read from download dir
        UniFile downloadDir = mSpiderDen.getDownloadDir();
        if (downloadDir != null) {
            UniFile file = downloadDir.findFile(SPIDER_INFO_FILENAME);
            if (file != null) {
                spiderInfo = SpiderInfoUtilsKt.readCompatFromUniFile(file);
                if (spiderInfo != null && spiderInfo.getGid() == mGalleryInfo.getGid() &&
                        spiderInfo.getToken().equals(mGalleryInfo.getToken())) {
                    return spiderInfo;
                }
            }
        }

        // Read from cache
        spiderInfo = SpiderInfoUtilsKt.readFromCache(mGalleryInfo.getGid());
        if (spiderInfo != null && spiderInfo.getGid() == mGalleryInfo.getGid() && spiderInfo.getToken().equals(mGalleryInfo.getToken())) {
            return spiderInfo;
        }
        return null;
    }

    private void readPreviews(String body, int index, SpiderInfo spiderInfo) throws ParseException {
        spiderInfo.setPreviewPages(GalleryDetailParser.parsePreviewPages(body));
        PreviewSet previewSet = GalleryDetailParser.parsePreviewSet(body);

        if (previewSet.size() > 0) {
            if (index == 0) {
                spiderInfo.setPreviewPerPage(previewSet.size());
            } else {
                spiderInfo.setPreviewPerPage(previewSet.getPosition(0) / index);
            }
        }

        for (int i = 0, n = previewSet.size(); i < n; i++) {
            GalleryPageUrlParser.Result result = GalleryPageUrlParser.parse(previewSet.getPageUrlAt(i));
            if (result != null) {
                synchronized (mPTokenLock) {
                    spiderInfo.getPTokenMap().put(result.page, result.pToken);
                }
            }
        }
    }

    private SpiderInfo readSpiderInfoFromInternet() {
        Request request = new EhRequestBuilder(EhUrl.getGalleryDetailUrl(
                mGalleryInfo.getGid(), mGalleryInfo.getToken(), 0, false), EhUrl.getReferer()).build();
        try (Response response = mHttpClient.newCall(request).execute()) {
            String body = response.body().string();

            var pages = GalleryDetailParser.parsePages(body);
            SpiderInfo spiderInfo = new SpiderInfo(mGalleryInfo.getGid(), pages);
            spiderInfo.setToken(mGalleryInfo.getToken());
            readPreviews(body, 0, spiderInfo);
            return spiderInfo;
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            return null;
        }
    }

    private String getPTokenFromMultiPageViewer(int index) {
        SpiderInfo spiderInfo = mSpiderInfo.get();
        if (spiderInfo == null) {
            return null;
        }

        String url = EhUrl.getGalleryMultiPageViewerUrl(
                mGalleryInfo.getGid(), mGalleryInfo.getToken());
        if (DEBUG_PTOKEN) {
            Log.d(TAG, "getPTokenFromMultiPageViewer index " + index + ", url " + url);
        }
        String referer = EhUrl.getReferer();
        Request request = new EhRequestBuilder(url, referer).build();
        try (Response response = mHttpClient.newCall(request).execute()) {
            String body = response.body().string();

            ArrayList<String> list = GalleryMultiPageViewerPTokenParser.parse(body);

            for (int i = 0; i < list.size(); i++) {
                synchronized (mPTokenLock) {
                    spiderInfo.getPTokenMap().put(i, list.get(i));
                }
            }

            String pToken;
            synchronized (mPTokenLock) {
                pToken = spiderInfo.getPTokenMap().get(index);
            }
            return pToken;
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            return null;
        }
    }

    private String getPTokenFromInternet(int index) {
        SpiderInfo spiderInfo = mSpiderInfo.get();
        if (spiderInfo == null) {
            return null;
        }

        // Check previewIndex
        int previewIndex;
        if (spiderInfo.getPreviewPerPage() >= 0) {
            previewIndex = index / spiderInfo.getPreviewPerPage();
        } else {
            previewIndex = 0;
        }
        if (spiderInfo.getPreviewPages() > 0) {
            previewIndex = Math.min(previewIndex, spiderInfo.getPreviewPages() - 1);
        }

        String url = EhUrl.getGalleryDetailUrl(
                mGalleryInfo.getGid(), mGalleryInfo.getToken(), previewIndex, false);
        String referer = EhUrl.getReferer();
        if (DEBUG_PTOKEN) {
            Log.d(TAG, "index " + index + ", previewIndex " + previewIndex +
                    ", previewPerPage " + spiderInfo.getPreviewPerPage() + ", url " + url);
        }
        Request request = new EhRequestBuilder(url, referer).build();
        try (Response response = mHttpClient.newCall(request).execute()) {
            String body = response.body().string();
            readPreviews(body, previewIndex, spiderInfo);

            String pToken;
            synchronized (mPTokenLock) {
                pToken = spiderInfo.getPTokenMap().get(index);
            }
            return pToken;
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            return null;
        }
    }

    private synchronized void writeSpiderInfoToLocal() {
        var spiderInfo = mSpiderInfo.get();
        if (spiderInfo != null) {
            UniFile downloadDir = mSpiderDen.getDownloadDir();
            if (downloadDir != null) {
                UniFile file = downloadDir.createFile(SPIDER_INFO_FILENAME);
                SpiderInfoUtilsKt.write(spiderInfo, file);
            }
            SpiderInfoUtilsKt.saveToCache(spiderInfo);
        }
    }

    private void runInternal() {
        // Read spider info
        SpiderInfo spiderInfo = readSpiderInfoFromLocal();

        // Check Stopped
        if (mStoped) {
            return;
        }

        // Spider info from internet
        if (spiderInfo == null) {
            spiderInfo = readSpiderInfoFromInternet();
        }

        // Error! Can't get spiderInfo
        if (spiderInfo == null) {
            return;
        }
        mSpiderInfo.set(spiderInfo);

        // Check Stopped
        if (mStoped) {
            return;
        }

        // Check Stopped

        // Setup page state
        synchronized (mPageStateLock) {
            mPageStateArray = new int[spiderInfo.getPages()];
        }

        // Notify get pages
        notifyGetPages(spiderInfo.getPages());

        // Ensure worker
        tryToEnsureWorkers();

        // Start decoder
        for (int i = 0; i < DECODE_THREAD_NUM; i++) {
            Thread decoderThread = new PriorityThread(new SpiderDecoder(i),
                    "SpiderDecoder-" + i, Process.THREAD_PRIORITY_DEFAULT);
            mDecodeThreadArray[i] = decoderThread;
            decoderThread.start();
        }

        // handle pToken request
        while (!mStoped) {
            Integer index = mRequestPTokenQueue.poll();

            if (index == null) {
                // No request index, wait here
                synchronized (mQueenLock) {
                    if (mStoped) break;
                    try {
                        mQueenLock.wait();
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (mStoped) break;
                }
                continue;
            }

            // Check it in spider info
            String pToken;
            synchronized (mPTokenLock) {
                pToken = spiderInfo.getPTokenMap().get(index);
            }
            if (pToken != null) {
                // Get pToken from spider info, notify worker
                synchronized (mWorkerLock) {
                    mWorkerLock.notifyAll();
                }
                continue;
            }

            // Get pToken from internet
            pToken = getPTokenFromInternet(index);
            if (null == pToken) {
                // Preview size may changed, so try to get pToken twice
                pToken = getPTokenFromInternet(index);
            }

            if (null == pToken) {
                // Multi-page viewer enabled maybe
                pToken = getPTokenFromMultiPageViewer(index);
            }

            if (null == pToken) {
                // If failed, set the pToken "failed"
                synchronized (mPTokenLock) {
                    spiderInfo.getPTokenMap().put(index, SpiderInfoUtilsKt.TOKEN_FAILED);
                }
            }

            // Notify worker
            synchronized (mWorkerLock) {
                mWorkerLock.notifyAll();
            }
        }
        writeSpiderInfoToLocal();
    }

    @Override
    public void run() {
        if (DEBUG_LOG) {
            Log.i(TAG, Thread.currentThread().getName() + ": start");
        }

        runInternal();

        // Set mQueenThread null
        mQueenThread = null;

        // Stop all workers
        synchronized (mWorkerLock) {
            mWorkerPoolExecutor.shutdown();
            mWorkerPoolExecutor = null;
        }
        notifyFinish();

        if (DEBUG_LOG) {
            Log.i(TAG, Thread.currentThread().getName() + ": end");
        }
    }

    private void updatePageState(int index, @State int state) {
        updatePageState(index, state, null);
    }

    private boolean isStateDone(int state) {
        return state == STATE_FINISHED || state == STATE_FAILED;
    }

    private void updatePageState(int index, @State int state, String error) {
        int oldState;
        synchronized (mPageStateLock) {
            oldState = mPageStateArray[index];
            mPageStateArray[index] = state;

            if (!isStateDone(oldState) && isStateDone(state)) {
                mDownloadedPages.incrementAndGet();
            } else if (isStateDone(oldState) && !isStateDone(state)) {
                mDownloadedPages.decrementAndGet();
            }
            if (oldState != STATE_FINISHED && state == STATE_FINISHED) {
                mFinishedPages.incrementAndGet();
            } else if (oldState == STATE_FINISHED && state != STATE_FINISHED) {
                mFinishedPages.decrementAndGet();
            }

            // Clear
            if (state == STATE_DOWNLOADING) {
                mPageErrorMap.remove(index);
            } else if (state == STATE_FINISHED || state == STATE_FAILED) {
                mPagePercentMap.remove(index);
            }

            // Get default error
            if (state == STATE_FAILED) {
                if (error == null) {
                    error = GetText.getString(R.string.error_unknown);
                }
                mPageErrorMap.put(index, error);
            }
        }

        // Notify listeners
        if (state == STATE_FAILED) {
            notifyPageFailure(index, error);
        } else if (state == STATE_FINISHED) {
            notifyPageSuccess(index);
        }
    }

    @IntDef({MODE_READ, MODE_DOWNLOAD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mode {
    }

    @IntDef({STATE_NONE, STATE_DOWNLOADING, STATE_FINISHED, STATE_FAILED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {
    }

    public interface OnSpiderListener {

        void onGetPages(int pages);

        void onGet509(int index);

        /**
         * @param contentLength -1 for unknown
         */
        void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead);

        void onPageSuccess(int index, int finished, int downloaded, int total);

        void onPageFailure(int index, String error, int finished, int downloaded, int total);

        /**
         * All workers end
         */
        void onFinish(int finished, int downloaded, int total);

        void onGetImageSuccess(int index, Image image);

        void onGetImageFailure(int index, String error);
    }

    private class SpiderWorker implements Runnable {

        private final long mGid;

        public SpiderWorker() {
            mGid = mGalleryInfo.getGid();
        }

        private String getPageUrl(long gid, int index, String pToken,
                                  String oldPageUrl, String skipHathKey) {
            String pageUrl = oldPageUrl != null ? oldPageUrl : EhUrl.getPageUrl(gid, index, pToken);
            // Add skipHathKey
            if (skipHathKey != null) {
                if (pageUrl.contains("?")) {
                    pageUrl += "&nl=" + skipHathKey;
                } else {
                    pageUrl += "?nl=" + skipHathKey;
                }
            }
            return pageUrl;
        }

        private GalleryPageParser.Result fetchPageResultFromHtml(int index, String pageUrl) throws Throwable {
            GalleryPageParser.Result result = EhEngine.getGalleryPage(pageUrl, mGalleryInfo.getGid(), mGalleryInfo.getToken());
            if (StringUtils.endsWith(result.imageUrl, URL_509_SUFFIX_ARRAY)) {
                // Get 509
                // Notify listeners
                notifyGet509(index);
                throw new Image509Exception();
            }

            return result;
        }

        private GalleryPageApiParser.Result fetchPageResultFromApi(long gid, int index, String pToken, String showKey, String previousPToken) throws Throwable {
            GalleryPageApiParser.Result result = EhEngine.getGalleryPageApi(gid, index, pToken, showKey, previousPToken);
            if (StringUtils.endsWith(result.imageUrl, URL_509_SUFFIX_ARRAY)) {
                // Get 509
                // Notify listeners
                notifyGet509(index);
                throw new Image509Exception();
            }

            return result;
        }

        // false for stop
        private boolean downloadImage(long gid, int index, String pToken, String previousPToken) {
            String skipHathKey = null;
            List<String> skipHathKeys = new ArrayList<>(5);
            String originImageUrl = null;
            String pageUrl = null;
            String error = null;
            boolean forceHtml = false;
            boolean leakSkipHathKey = false;

            for (int i = 0; i < 2; i++) {
                String imageUrl = null;
                String localShowKey;

                // Check show key
                synchronized (showKeyLock) {
                    localShowKey = showKey.get();
                    if (localShowKey == null || forceHtml) {
                        if (leakSkipHathKey) {
                            break;
                        }

                        // Try to get show key
                        pageUrl = getPageUrl(gid, index, pToken, pageUrl, skipHathKey);
                        try {
                            GalleryPageParser.Result result = fetchPageResultFromHtml(index, pageUrl);
                            imageUrl = result.imageUrl;
                            skipHathKey = result.skipHathKey;
                            originImageUrl = result.originImageUrl;
                            localShowKey = result.showKey;

                            if (!TextUtils.isEmpty(skipHathKey)) {
                                if (skipHathKeys.contains(skipHathKey)) {
                                    // Duplicate skip hath key
                                    leakSkipHathKey = true;
                                } else {
                                    skipHathKeys.add(skipHathKey);
                                }
                            } else {
                                leakSkipHathKey = true;
                            }

                            showKey.lazySet(result.showKey);
                        } catch (Image509Exception e) {
                            error = GetText.getString(R.string.error_509);
                            break;
                        } catch (Throwable e) {
                            ExceptionUtils.throwIfFatal(e);
                            error = ExceptionUtils.getReadableString(e);
                            break;
                        }

                        // Check Stopped
                        if (mStoped) {
                            error = "Interrupted";
                            break;
                        }
                    }
                }

                if (imageUrl == null) {
                    if (localShowKey == null) {
                        error = "ShowKey error";
                        break;
                    }

                    try {
                        GalleryPageApiParser.Result result = fetchPageResultFromApi(gid, index, pToken, localShowKey, previousPToken);
                        imageUrl = result.imageUrl;
                        skipHathKey = result.skipHathKey;
                        originImageUrl = result.originImageUrl;
                    } catch (Image509Exception e) {
                        error = GetText.getString(R.string.error_509);
                        break;
                    } catch (Throwable e) {
                        if (e instanceof ParseException && "Key mismatch".equals(e.getMessage())) {
                            // Show key is wrong, enter a new loop to get the new show key
                            showKey.compareAndSet(localShowKey, null);
                            continue;
                        } else {
                            ExceptionUtils.throwIfFatal(e);
                            error = ExceptionUtils.getReadableString(e);
                            break;
                        }
                    }

                    // Check Stopped
                    if (mStoped) {
                        error = "Interrupted";
                        break;
                    }
                }

                String targetImageUrl;
                String referer;
                if (Settings.getDownloadOriginImage() && !TextUtils.isEmpty(originImageUrl)) {
                    targetImageUrl = originImageUrl;
                    referer = EhUrl.getPageUrl(gid, index, pToken);
                } else {
                    targetImageUrl = imageUrl;
                    referer = null;
                }
                if (targetImageUrl == null) {
                    error = "TargetImageUrl error";
                    break;
                }
                if (DEBUG_LOG) {
                    Log.d(TAG, targetImageUrl);
                }

                // Download image
                try {
                    if (DEBUG_LOG) {
                        Log.d(TAG, "Start download image " + index);
                    }

                    var success = mSpiderDen.makeHttpCallAndSaveImage(index, targetImageUrl, referer, (contentLength, receivedSize, bytesRead) -> {
                        if (mStoped) throw new CancellationException();
                        mPagePercentMap.put(index, (float) receivedSize / contentLength);
                        notifyPageDownload(index, contentLength, receivedSize, bytesRead);
                        return null;
                    });

                    // Check Stopped
                    if (mStoped) {
                        error = "Interrupted";
                        break;
                    }

                    if (!success) {
                        Log.e(TAG, "Can't download all of image data");
                        error = "Incomplete";
                        forceHtml = true;
                        continue;
                    }

                    if (mSpiderDen.checkPlainText(index)) {
                        error = GetText.getString(R.string.error_reading_failed);
                        forceHtml = true;
                        continue;
                    }

                    // Check Stopped
                    if (mStoped) {
                        error = "Interrupted";
                        break;
                    }

                    if (DEBUG_LOG) {
                        Log.d(TAG, "Download image succeed " + index);
                    }

                    // Download finished
                    updatePageState(index, STATE_FINISHED);
                    try {
                        Thread.sleep(mDownloadDelay);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    return true;
                } catch (IOException e) {
                    e.printStackTrace();
                    error = GetText.getString(R.string.error_socket);
                    forceHtml = true;
                } finally {
                    if (DEBUG_LOG) {
                        Log.d(TAG, "End download image " + index);
                    }
                }
            }

            // Remove download failed image
            mSpiderDen.remove(index);

            updatePageState(index, STATE_FAILED, error);
            return !mStoped;
        }

        // false for stop
        private boolean runInternal() {
            SpiderInfo spiderInfo = mSpiderInfo.get();
            if (spiderInfo == null) {
                return false;
            }

            int size = mPageStateArray.length;

            // Get request index
            int index;
            // From force request
            boolean force = false;
            synchronized (mRequestPageQueue) {
                if (!mForceRequestPageQueue.isEmpty()) {
                    index = mForceRequestPageQueue.remove();
                    force = true;
                } else if (!mRequestPageQueue.isEmpty()) {
                    index = mRequestPageQueue.remove();
                } else if (!mRequestPageQueue2.isEmpty()) {
                    index = mRequestPageQueue2.remove();
                } else if (mDownloadPage >= 0 && mDownloadPage < size) {
                    index = mDownloadPage;
                    mDownloadPage++;
                } else {
                    // No index any more, stop
                    return false;
                }

                // Check out of range
                if (index < 0 || index >= size) {
                    // Invalid index
                    return true;
                }
            }

            synchronized (mPageStateLock) {
                // Check the page state
                int state = mPageStateArray[index];
                if (state == STATE_DOWNLOADING || (!force && (state == STATE_FINISHED || state == STATE_FAILED))) {
                    return true;
                }

                // Set state downloading
                updatePageState(index, STATE_DOWNLOADING);
            }

            // Check exist for not force request
            if (!force && mSpiderDen.contain(index)) {
                updatePageState(index, STATE_FINISHED);
                return true;
            }

            // Clear TOKEN_FAILED for force request
            if (force) {
                synchronized (mPTokenLock) {
                    String pToken = spiderInfo.getPTokenMap().get(index);
                    if (SpiderInfoUtilsKt.TOKEN_FAILED.equals(pToken)) {
                        spiderInfo.getPTokenMap().remove(index);
                    }
                }
            }

            String pToken = null;
            // Get token
            while (!mStoped) {
                synchronized (mPTokenLock) {
                    pToken = spiderInfo.getPTokenMap().get(index);
                }
                if (pToken == null) {
                    mRequestPTokenQueue.add(index);
                    // Notify Queen
                    synchronized (mQueenLock) {
                        mQueenLock.notify();
                    }
                    // Wait
                    synchronized (mWorkerLock) {
                        if (mStoped) break;
                        try {
                            mWorkerLock.wait();
                        } catch (InterruptedException e) {
                            // Interrupted
                            if (DEBUG_LOG) {
                                Log.d(TAG, Thread.currentThread().getName() + " Interrupted");
                            }
                            break;
                        }
                        if (mStoped) break;
                    }
                } else {
                    break;
                }
            }

            if (pToken == null) {
                // Interrupted
                // Get token failed
                updatePageState(index, STATE_FAILED, "Interrupted");
                return false;
            }

            String previousPToken = null;
            int previousIndex = index - 1;
            // Get token
            while (previousIndex >= 0 && !mStoped) {
                synchronized (mPTokenLock) {
                    previousPToken = spiderInfo.getPTokenMap().get(previousIndex);
                }
                if (previousPToken == null) {
                    mRequestPTokenQueue.add(previousIndex);
                    // Notify Queen
                    synchronized (mQueenLock) {
                        mQueenLock.notify();
                    }
                    // Wait
                    synchronized (mWorkerLock) {
                        if (mStoped) break;
                        try {
                            mWorkerLock.wait();
                        } catch (InterruptedException e) {
                            // Interrupted
                            if (DEBUG_LOG) {
                                Log.d(TAG, Thread.currentThread().getName() + " Interrupted");
                            }
                            break;
                        }
                        if (mStoped) break;
                    }
                } else {
                    break;
                }
            }

            if (SpiderInfoUtilsKt.TOKEN_FAILED.equals(pToken)) {
                // Get token failed
                updatePageState(index, STATE_FAILED, GetText.getString(R.string.error_get_ptoken_error));
                return true;
            }

            // Get image url
            return downloadImage(mGid, index, pToken, previousPToken);
        }

        @Override
        @SuppressWarnings("StatementWithEmptyBody")
        public void run() {
            if (DEBUG_LOG) {
                Log.i(TAG, Thread.currentThread().getName() + ": start");
            }

            while (mSpiderDen.isReady() && !mStoped && runInternal()) ;

            boolean finish;
            // Clear in spider worker array
            synchronized (mWorkerLock) {
                mWorkerCount--;
                if (mWorkerCount < 0) {
                    Log.e(TAG, "WTF, mWorkerCount < 0, not thread safe or something wrong");
                    mWorkerCount = 0;
                }
                finish = mWorkerCount == 0;
            }

            if (finish) {
                notifyFinish();
            }

            if (DEBUG_LOG) {
                Log.i(TAG, Thread.currentThread().getName() + ": end");
            }
        }
    }

    private class SpiderDecoder implements Runnable {

        private final int mThreadIndex;

        public SpiderDecoder(int index) {
            mThreadIndex = index;
        }

        private void resetDecodeIndex() {
            synchronized (mDecodeRequestQueue) {
                mDecodeIndexArray[mThreadIndex] = -1;
            }
        }

        @Override
        public void run() {
            if (DEBUG_LOG) {
                Log.i(TAG, Thread.currentThread().getName() + ": start");
            }

            while (!mStoped) {
                int index;
                synchronized (mDecodeRequestQueue) {
                    if (mStoped) break;
                    if (mDecodeRequestQueue.isEmpty()) {
                        try {
                            mDecodeRequestQueue.wait();
                        } catch (InterruptedException e) {
                            // Interrupted
                            break;
                        }
                        if (mStoped) break;
                        continue;
                    }
                    index = mDecodeRequestQueue.remove();
                    mDecodeIndexArray[mThreadIndex] = index;
                }

                // Check index valid
                if (index < 0 || index >= mPageStateArray.length) {
                    resetDecodeIndex();
                    notifyGetImageFailure(index, GetText.getString(R.string.error_out_of_range));
                    continue;
                }

                Image.ByteBufferSource src = mSpiderDen.getImageSource(index);
                if (src == null) {
                    resetDecodeIndex();
                    // Can't find the file, it might be removed from cache,
                    // Reset it state and request it
                    updatePageState(index, STATE_NONE, null);
                    request(index, false, false, false);
                    continue;
                }

                Image image = Image.decode(src);
                String error = null;

                if (image == null) {
                    error = GetText.getString(R.string.error_decoding_failed);
                }

                // Notify
                if (image != null) {
                    notifyGetImageSuccess(index, image);
                } else {
                    notifyGetImageFailure(index, error);
                }

                resetDecodeIndex();
            }

            if (DEBUG_LOG) {
                Log.i(TAG, Thread.currentThread().getName() + ": end");
            }
        }
    }
}
