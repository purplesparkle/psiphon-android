/*
 *
 * Copyright (c) 2019, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.google.ads.consent.ConsentForm;
import com.google.ads.consent.ConsentFormListener;
import com.google.ads.consent.ConsentInfoUpdateListener;
import com.google.ads.consent.ConsentInformation;
import com.google.ads.consent.ConsentStatus;
import com.google.ads.consent.DebugGeography;
import com.google.ads.mediation.admob.AdMobAdapter;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.InterstitialAd;
import com.google.android.gms.ads.MobileAds;
import com.google.auto.value.AutoValue;
import com.jakewharton.rxrelay2.PublishRelay;
import com.mopub.common.MoPub;
import com.mopub.common.SdkConfiguration;
import com.mopub.common.SdkInitializationListener;
import com.mopub.common.privacy.ConsentDialogListener;
import com.mopub.common.privacy.PersonalInfoManager;
import com.mopub.mobileads.MoPubErrorCode;
import com.mopub.mobileads.MoPubInterstitial;
import com.mopub.mobileads.MoPubView;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.Utils;

import net.grandcentrix.tray.AppPreferences;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;

public class PsiphonAdManager {

    @AutoValue
    static abstract class AdResult {
        public enum Type {TUNNELED, UNTUNNELED, NONE}

        @NonNull
        static AdResult tunneled(TunnelState.ConnectionData connectionData) {
            return new AutoValue_PsiphonAdManager_AdResult(Type.TUNNELED, connectionData);
        }

        static AdResult unTunneled() {
            return new AutoValue_PsiphonAdManager_AdResult(Type.UNTUNNELED, null);
        }

        static AdResult none() {
            return new AutoValue_PsiphonAdManager_AdResult(Type.NONE, null);
        }

        public abstract Type type();

        @Nullable
        abstract TunnelState.ConnectionData connectionData();
    }

    interface InterstitialResult {
        enum State {LOADING, READY, SHOWING}

        State state();

        void show();

        @AutoValue
        abstract class MoPub implements InterstitialResult {
            public void show() {
                interstitial().show();
            }

            abstract MoPubInterstitial interstitial();

            public abstract State state();

            @NonNull
            static InterstitialResult create(MoPubInterstitial interstitial, State state) {
                return new AutoValue_PsiphonAdManager_InterstitialResult_MoPub(interstitial, state);
            }

        }

        @AutoValue
        abstract class AdMob implements InterstitialResult {

            public void show() {
                interstitial().show();
            }

            abstract InterstitialAd interstitial();

            public abstract State state();

            @NonNull
            static AdMob create(InterstitialAd interstitial, State state) {
                return new AutoValue_PsiphonAdManager_InterstitialResult_AdMob(interstitial, state);
            }
        }
    }

    static final String TAG = "PsiphonAdManager";

    // ----------Production values -----------
    private static final String ADMOB_UNTUNNELED_BANNER_PROPERTY_ID = "ca-app-pub-1072041961750291/7238659523";
    private static final String ADMOB_UNTUNNELED_LARGE_BANNER_PROPERTY_ID = "ca-app-pub-1072041961750291/3275363789";
    private static final String ADMOB_UNTUNNELED_INTERSTITIAL_PROPERTY_ID = "ca-app-pub-1072041961750291/9298519104";
    private static final String MOPUB_TUNNELED_BANNER_PROPERTY_ID = "6848f6c3bce64522b771ea8ce9b5f1cd";
    private static final String MOPUB_TUNNELED_LARGE_BANNER_PROPERTY_ID = "0ad7bcfc9b17444aa80b1c198e5ebda5";
    private static final String MOPUB_TUNNELED_INTERSTITIAL_PROPERTY_ID = "b17a746d77c9436bb805c958f7879342";

    private AdView unTunneledAdMobBannerAdView = null;
    private MoPubView tunneledMoPubBannerAdView = null;

    private final InterstitialAd unTunneledAdMobInterstitial;
    private final MoPubInterstitial tunneledMoPubInterstitial;

    private ViewGroup bannerLayout;
    private ConsentForm adMobConsentForm;

    private Activity activity;
    private int tabChangeCount = 0;

    private final Completable initializeMoPubSdk;
    private final Completable initializeAdMobSdk;

    private final Observable<AdResult> currentAdTypeObservable;
    private Disposable loadAdsDisposable;
    private Disposable tunneledInterstitialDisposable;
    private PublishRelay<TunnelState> tunnelConnectionStatePublishRelay = PublishRelay.create();

    private final Observable<InterstitialResult> unTunneledAdMobInterstitialObservable;
    private final Observable<InterstitialResult> tunneledMoPubInterstitialObservable;

    PsiphonAdManager(Activity activity, ViewGroup bannerLayout) {
        this.activity = activity;
        this.bannerLayout = bannerLayout;

        // MoPub SDK is also tracking GDPR status and will present a GDPR consent collection dialog if needed.
        this.initializeMoPubSdk = Completable.create(emitter -> {
            MoPub.setLocationAwareness(MoPub.LocationAwareness.DISABLED);
            SdkConfiguration.Builder builder = new SdkConfiguration.Builder(MOPUB_TUNNELED_LARGE_BANNER_PROPERTY_ID);
            SdkConfiguration sdkConfiguration = builder.build();
            SdkInitializationListener sdkInitializationListener = () -> {
                final PersonalInfoManager personalInfoManager = MoPub.getPersonalInformationManager();
                if (personalInfoManager != null) {
                    // subscribe to consent change state event
                    personalInfoManager.subscribeConsentStatusChangeListener((oldConsentStatus, newConsentStatus, canCollectPersonalInformation) -> {
                        if (personalInfoManager.shouldShowConsentDialog()) {
                            personalInfoManager.loadConsentDialog(moPubConsentDialogListener());
                        }
                    });
                    // If consent is required load the consent dialog
                    if (personalInfoManager.shouldShowConsentDialog()) {
                        personalInfoManager.loadConsentDialog(moPubConsentDialogListener());
                    }
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                } else {
                    if (!emitter.isDisposed()) {
                        emitter.onError(new RuntimeException("MoPub SDK has failed to initialize, MoPub.getPersonalInformationManager is null"));
                    }
                }
            };
            MoPub.initializeSdk(activity, sdkConfiguration, sdkInitializationListener);
        })
                .doOnError(e -> Utils.MyLog.d("initializeMoPubSdk error: " + e))
                .cache();

        this.initializeAdMobSdk = Completable.create(emitter -> {
            MobileAds.initialize(this.activity, BuildConfig.ADMOB_APP_ID);
            if (!emitter.isDisposed()) {
                emitter.onComplete();
            }
        })
                .cache();

        // Note this observable also destroys ads according to subscription and/or
        // connection status without further delay.
        this.currentAdTypeObservable = tunnelConnectionStateObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .flatMap(s -> {
                    if(!shouldShowAds()) {
                        destroyAllAds();
                        return Observable.just(AdResult.none());
                    }
                    if (s.isRunning() && s.connectionData().isConnected()) {
                        // Tunnel is connected.
                        // Destroy untunneled banners and send AdResult.TUNNELED
                        destroyUnTunneledBanners();
                        return Observable.just(AdResult.tunneled(s.connectionData()));
                    } else if (s.isStopped()) {
                        // Service is not running, destroy tunneled banners and send AdResult.UNTUNNELED
                        destroyTunneledBanners();
                        // Unlike MoPub, AdMob consent update listener is not a part of SDK initialization
                        // and we need to run the check every time. This call doesn't need to be synced with
                        // creation and deletion of ad views.
                        runAdMobGdprCheck();
                        return Observable.just(AdResult.unTunneled());
                    } else {
                        // Tunnel is either in unknown or connecting state.
                        // Destroy all banners and send AdResult.NONE.
                        destroyTunneledBanners();
                        destroyUnTunneledBanners();
                        return Observable.just(AdResult.none());
                    }
                })
                .replay(1)
                .autoConnect(0);

        this.unTunneledAdMobInterstitial = new InterstitialAd(activity);
        this.unTunneledAdMobInterstitial.setAdUnitId(ADMOB_UNTUNNELED_INTERSTITIAL_PROPERTY_ID);

        this.unTunneledAdMobInterstitialObservable = initializeAdMobSdk
                .andThen(Observable.<InterstitialResult>create(emitter -> {
                    unTunneledAdMobInterstitial.setAdListener(new AdListener() {

                        @Override
                        public void onAdLoaded() {
                            if (!emitter.isDisposed()) {
                                emitter.onNext(InterstitialResult.AdMob.create(unTunneledAdMobInterstitial, InterstitialResult.State.READY));
                            }
                        }

                        @Override
                        public void onAdFailedToLoad(int errorCode) {
                            if (!emitter.isDisposed()) {
                                emitter.onError(new RuntimeException("AdMob interstitial failed with error code: " + errorCode));
                            }
                        }

                        @Override
                        public void onAdOpened() {
                            if (!emitter.isDisposed()) {
                                emitter.onNext(InterstitialResult.AdMob.create(unTunneledAdMobInterstitial, InterstitialResult.State.SHOWING));
                            }
                        }

                        @Override
                        public void onAdLeftApplication() {
                        }

                        @Override
                        public void onAdClosed() {
                            if (!emitter.isDisposed()) {
                                emitter.onComplete();
                            }
                        }
                    });
                    Bundle extras = new Bundle();
                    if (ConsentInformation.getInstance(activity).getConsentStatus() == ConsentStatus.NON_PERSONALIZED) {
                        extras.putString("npa", "1");
                    }
                    AdRequest adRequest = new AdRequest.Builder()
                            .addNetworkExtrasBundle(AdMobAdapter.class, extras)
                            .build();
                    if (unTunneledAdMobInterstitial.isLoaded()) {
                        if (!emitter.isDisposed()) {
                            emitter.onNext(InterstitialResult.AdMob.create(unTunneledAdMobInterstitial, InterstitialResult.State.READY));
                        }
                    } else if (unTunneledAdMobInterstitial.isLoading()) {
                        if (!emitter.isDisposed()) {
                            emitter.onNext(InterstitialResult.AdMob.create(unTunneledAdMobInterstitial, InterstitialResult.State.LOADING));
                        }
                    } else {
                        if (!emitter.isDisposed()) {
                            emitter.onNext(InterstitialResult.AdMob.create(unTunneledAdMobInterstitial, InterstitialResult.State.LOADING));
                            unTunneledAdMobInterstitial.loadAd(adRequest);
                        }
                    }
                }))
                .replay(1)
                .refCount();

        this.tunneledMoPubInterstitial = new MoPubInterstitial(activity, MOPUB_TUNNELED_INTERSTITIAL_PROPERTY_ID);
        this.tunneledMoPubInterstitialObservable = initializeMoPubSdk
                .andThen(Observable.<InterstitialResult>create(emitter -> {
                    tunneledMoPubInterstitial.setInterstitialAdListener(new MoPubInterstitial.InterstitialAdListener() {
                        @Override
                        public void onInterstitialLoaded(MoPubInterstitial interstitial) {
                            if (!emitter.isDisposed()) {
                                emitter.onNext(InterstitialResult.MoPub.create(interstitial, InterstitialResult.State.READY));
                            }
                        }

                        @Override
                        public void onInterstitialFailed(MoPubInterstitial interstitial, MoPubErrorCode errorCode) {
                            if (!emitter.isDisposed()) {
                                emitter.onError(new RuntimeException("MoPub interstitial failed: " + errorCode.toString()));
                            }
                        }

                        @Override
                        public void onInterstitialShown(MoPubInterstitial interstitial) {
                            if (!emitter.isDisposed()) {
                                emitter.onNext(InterstitialResult.MoPub.create(interstitial, InterstitialResult.State.SHOWING));
                            }
                        }

                        @Override
                        public void onInterstitialClicked(MoPubInterstitial interstitial) {
                        }

                        @Override
                        public void onInterstitialDismissed(MoPubInterstitial interstitial) {
                            if (!emitter.isDisposed()) {
                                emitter.onComplete();
                            }
                            interstitial.load();
                        }
                    });
                    if (tunneledMoPubInterstitial.isReady()) {
                        if (!emitter.isDisposed()) {
                            emitter.onNext(InterstitialResult.MoPub.create(tunneledMoPubInterstitial, InterstitialResult.State.READY));
                        }
                    } else {
                        if (!emitter.isDisposed()) {
                            emitter.onNext(InterstitialResult.MoPub.create(tunneledMoPubInterstitial, InterstitialResult.State.LOADING));
                            tunneledMoPubInterstitial.load();
                        }
                    }
                }))
                .replay(1)
                .refCount();
    }

    boolean shouldShowAds() {
        AppPreferences appPreferences = new AppPreferences(activity);
        return appPreferences.getBoolean(activity.getString(R.string.persistent_show_ads_setting), false) &&
                !EmbeddedValues.hasEverBeenSideLoaded(activity) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN;
    }

    Observable<AdResult> getCurrentAdTypeObservable() {
        return currentAdTypeObservable;
    }

    private void runAdMobGdprCheck() {
        String[] publisherIds = {"pub-1072041961750291"};
        ConsentInformation.getInstance(activity).
                setDebugGeography(DebugGeography.DEBUG_GEOGRAPHY_EEA);
        ConsentInformation.getInstance(activity).requestConsentInfoUpdate(publisherIds, new ConsentInfoUpdateListener() {
            @Override
            public void onConsentInfoUpdated(ConsentStatus consentStatus) {
                if (consentStatus == ConsentStatus.UNKNOWN
                        && ConsentInformation.getInstance(activity).isRequestLocationInEeaOrUnknown()) {
                    URL privacyUrl;
                    try {
                        privacyUrl = new URL(EmbeddedValues.DATA_COLLECTION_INFO_URL);
                    } catch (MalformedURLException e) {
                        Utils.MyLog.d("Can't create privacy URL for AdMob consent form: " + e);
                        return;
                    }
                    if (adMobConsentForm == null) {
                        adMobConsentForm = new ConsentForm.Builder(activity, privacyUrl)
                                .withListener(new ConsentFormListener() {
                                    @Override
                                    public void onConsentFormLoaded() {
                                        // Consent form loaded successfully.
                                        //
                                        // See https://github.com/googleads/googleads-consent-sdk-android/issues/74
                                        // Calling adMobConsentForm.show() may throw android.view.WindowManager$BadTokenException
                                        // if activity is finishing, we will also surround the call with try/catch block just in case.
                                        if (adMobConsentForm != null && !adMobConsentForm.isShowing() && !activity.isFinishing()) {
                                            try {
                                                adMobConsentForm.show();
                                            } catch (WindowManager.BadTokenException e) {
                                                Utils.MyLog.g("AdMob: consent form show error: " + e);
                                            }
                                        }
                                    }

                                    @Override
                                    public void onConsentFormOpened() {
                                    }

                                    @Override
                                    public void onConsentFormClosed(ConsentStatus consentStatus, Boolean userPrefersAdFree) {
                                    }

                                    @Override
                                    public void onConsentFormError(String errorDescription) {
                                        Utils.MyLog.d("AdMob consent form error: " + errorDescription);
                                    }
                                })
                                .withPersonalizedAdsOption()
                                .withNonPersonalizedAdsOption()
                                .build();
                    }
                    adMobConsentForm.load();
                }
            }

            @Override
            public void onFailedToUpdateConsentInfo(String errorDescription) {
                Utils.MyLog.d("AdMob consent failed to update: " + errorDescription);
            }
        });
    }

    void onTunnelConnectionState(TunnelState state) {
        tunnelConnectionStatePublishRelay.accept(state);
    }

    void onTabChanged() {
        if (tunneledInterstitialDisposable != null && !tunneledInterstitialDisposable.isDisposed()) {
            // subscription in progress, do nothing
            return;
        }
        // First tab change triggers the interstitial
        // NOTE: tabChangeCount gets reset when we ads change to AdsType.TUNNELED
        if (tabChangeCount % 3 != 0) {
            tabChangeCount++;
            return;
        }

        tunneledInterstitialDisposable = getCurrentAdTypeObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .takeWhile(adResult -> adResult.type() == AdResult.Type.TUNNELED)
                .take(1)
                .compose(getInterstitialWithTimeoutForAdType(3, TimeUnit.SECONDS))
                .doOnNext(interstitialResult -> {
                    interstitialResult.show();
                    tabChangeCount++;
                })
                .onErrorResumeNext(Observable.empty())
                .subscribe();
    }

    void startLoadingAds() {
        if (loadAdsDisposable != null && !loadAdsDisposable.isDisposed()) {
            return;
        }
        loadAdsDisposable = getCurrentAdTypeObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .distinctUntilChanged()
                .doOnNext(adResult -> {
                    if (adResult.type() == AdResult.Type.TUNNELED) {
                        // reset tabChange every time we go TUNNELED
                        tabChangeCount = 0;
                    }
                })
                .publish(shared -> Observable.mergeDelayError(
                        shared.switchMap(adResult -> loadInterstitialForAdResult(adResult).onErrorResumeNext(Observable.empty())),
                        shared.switchMap(adResult -> loadAndShowBanner(adResult).toObservable())))
                .onErrorResumeNext(Observable.empty())
                .subscribe();
    }

    ObservableTransformer<AdResult, InterstitialResult> getInterstitialWithTimeoutForAdType(int timeout, TimeUnit timeUnit) {
        return observable -> observable
                .observeOn(AndroidSchedulers.mainThread())
                .switchMap(this::loadInterstitialForAdResult)
                .ambWith(Observable.timer(timeout, timeUnit)
                        .flatMap(l -> Observable.error(new TimeoutException("getInterstitialWithTimeoutForAdType timed out."))));
    }

    private Observable<TunnelState> tunnelConnectionStateObservable() {
        return tunnelConnectionStatePublishRelay.hide().distinctUntilChanged();
    }

    private Completable loadAndShowBanner(AdResult adResult) {
        Completable completable;

        boolean isPortraitOrientation =
                activity.getApplicationContext().getResources().getConfiguration().orientation ==
                        Configuration.ORIENTATION_PORTRAIT;

        switch (adResult.type()) {
            case NONE:
                completable = Completable.complete();
                break;
            case TUNNELED:
                completable = initializeMoPubSdk.andThen(Completable.fromAction(() -> {
                    tunneledMoPubBannerAdView = new MoPubView(activity);
                    tunneledMoPubBannerAdView.setAdUnitId(isPortraitOrientation && new Random().nextBoolean() ?
                            MOPUB_TUNNELED_LARGE_BANNER_PROPERTY_ID :
                            MOPUB_TUNNELED_BANNER_PROPERTY_ID);
                    TunnelState.ConnectionData connectionData = adResult.connectionData();
                    if (connectionData != null) {
                        tunneledMoPubBannerAdView.setKeywords("client_region:" + connectionData.clientRegion());
                        Map<String, Object> localExtras = new HashMap<>();
                        localExtras.put("client_region", connectionData.clientRegion());
                        tunneledMoPubBannerAdView.setLocalExtras(localExtras);
                    }
                    tunneledMoPubBannerAdView.setBannerAdListener(new MoPubView.BannerAdListener() {
                        @Override
                        public void onBannerLoaded(MoPubView banner) {
                            if (tunneledMoPubBannerAdView.getParent() == null) {
                                bannerLayout.removeAllViewsInLayout();
                                bannerLayout.addView(tunneledMoPubBannerAdView);
                            }
                        }

                        @Override
                        public void onBannerClicked(MoPubView arg0) {
                        }

                        @Override
                        public void onBannerCollapsed(MoPubView arg0) {
                        }

                        @Override
                        public void onBannerExpanded(MoPubView arg0) {
                        }

                        @Override
                        public void onBannerFailed(MoPubView v, MoPubErrorCode errorCode) {
                        }
                    });
                    tunneledMoPubBannerAdView.loadAd();
                    tunneledMoPubBannerAdView.setAutorefreshEnabled(true);
                }));
                break;
            case UNTUNNELED:
                completable = initializeAdMobSdk.andThen(Completable.fromAction(() -> {
                    unTunneledAdMobBannerAdView = new AdView(activity);
                    if(isPortraitOrientation) {
                        unTunneledAdMobBannerAdView.setAdSize(AdSize.MEDIUM_RECTANGLE);
                        unTunneledAdMobBannerAdView.setAdUnitId(ADMOB_UNTUNNELED_LARGE_BANNER_PROPERTY_ID);
                    } else {
                        unTunneledAdMobBannerAdView.setAdSize(AdSize.SMART_BANNER);
                        unTunneledAdMobBannerAdView.setAdUnitId(ADMOB_UNTUNNELED_BANNER_PROPERTY_ID);
                    }
                    unTunneledAdMobBannerAdView.setAdListener(new AdListener() {
                        @Override
                        public void onAdLoaded() {
                            if (unTunneledAdMobBannerAdView.getParent() == null) {
                                bannerLayout.removeAllViewsInLayout();
                                bannerLayout.addView(unTunneledAdMobBannerAdView);
                            }
                        }

                        @Override
                        public void onAdFailedToLoad(int errorCode) {
                        }

                        @Override
                        public void onAdOpened() {
                        }

                        @Override
                        public void onAdLeftApplication() {
                        }

                        @Override
                        public void onAdClosed() {
                        }
                    });
                    Bundle extras = new Bundle();
                    if (ConsentInformation.getInstance(activity).getConsentStatus() == com.google.ads.consent.ConsentStatus.NON_PERSONALIZED) {
                        extras.putString("npa", "1");
                    }
                    AdRequest adRequest = new AdRequest.Builder()
                            .addNetworkExtrasBundle(AdMobAdapter.class, extras)
                            .build();
                    unTunneledAdMobBannerAdView.loadAd(adRequest);
                }));
                break;
            default:
                throw new IllegalArgumentException("loadAndShowBanner: unhandled AdResult.Type: " + adResult.type());
        }
        return completable
                .doOnError(e -> Utils.MyLog.g("loadAndShowBanner: error: " + e))
                .onErrorComplete();
    }

    private Observable<InterstitialResult> loadInterstitialForAdResult(final AdResult adResult) {
        AdResult.Type adType = adResult.type();
        switch (adType) {
            case NONE:
                return Observable.empty();
            case TUNNELED:
                // Set client region data on the ad
                TunnelState.ConnectionData connectionData = adResult.connectionData();
                if (connectionData != null) {
                    tunneledMoPubInterstitial.setKeywords("client_region:" + connectionData.clientRegion());
                    Map<String, Object> localExtras = new HashMap<>();
                    localExtras.put("client_region", connectionData.clientRegion());
                    tunneledMoPubInterstitial.setLocalExtras(localExtras);
                }
                return tunneledMoPubInterstitialObservable;
            case UNTUNNELED:
                return unTunneledAdMobInterstitialObservable;
            default:
                throw new IllegalArgumentException("loadInterstitialForAdResult unhandled AdResult.Type: " + adType);
        }
    }

    private void destroyTunneledBanners() {
        if (tunneledMoPubBannerAdView != null) {
            ViewGroup parent = (ViewGroup) tunneledMoPubBannerAdView.getParent();
            if (parent != null) {
                parent.removeView(tunneledMoPubBannerAdView);
            }
            tunneledMoPubBannerAdView.destroy();
        }
    }

    private void destroyUnTunneledBanners() {
        if (unTunneledAdMobBannerAdView != null) {
            // AdMob's AdView may still call its listener even after a call to destroy();
            unTunneledAdMobBannerAdView.setAdListener(null);
            ViewGroup parent = (ViewGroup) unTunneledAdMobBannerAdView.getParent();
            if (parent != null) {
                parent.removeView(unTunneledAdMobBannerAdView);
            }
            unTunneledAdMobBannerAdView.destroy();
        }
    }

    private void destroyAllAds() {
        destroyTunneledBanners();
        destroyUnTunneledBanners();
        tunneledMoPubInterstitial.destroy();
        unTunneledAdMobInterstitial.setAdListener(null);
    }

    // implementing Activity must call this on its onDestroy lifecycle callback
    public void onDestroy() {
        destroyAllAds();
        if (loadAdsDisposable != null) {
            loadAdsDisposable.dispose();
        }
        if (tunneledInterstitialDisposable != null) {
            tunneledInterstitialDisposable.dispose();
        }
    }

    static ConsentDialogListener moPubConsentDialogListener() {
        return new ConsentDialogListener() {
            @Override
            public void onConsentDialogLoaded() {
                PersonalInfoManager personalInfoManager = MoPub.getPersonalInformationManager();
                if (personalInfoManager != null) {
                    personalInfoManager.showConsentDialog();
                }
            }

            @Override
            public void onConsentDialogLoadFailed(@NonNull MoPubErrorCode moPubErrorCode) {
                Utils.MyLog.d("MoPub consent dialog load error: " + moPubErrorCode.toString());
            }
        };
    }
}
