.class public Lcom/nishgamerrz/relaydash/MainActivity;
.super Landroid/app/Activity;
.source "MainActivity.java"


# instance fields
.field private bridge:Lcom/nishgamerrz/relaydash/JsBridge;

.field private launchUrl:Ljava/lang/String;

.field private web:Landroid/webkit/WebView;


# direct methods
.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Landroid/app/Activity;-><init>()V

    return-void
.end method


# virtual methods
.method public loadUrl(Ljava/lang/String;)V
    .locals 2

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/MainActivity;->web:Landroid/webkit/WebView;

    if-nez v0, :cond_ok

    return-void

    :cond_ok
    invoke-virtual {v0, p1}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V

    return-void
.end method

.method public runJs(Ljava/lang/String;)V
    .locals 2

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/MainActivity;->web:Landroid/webkit/WebView;

    if-nez v0, :cond_ok

    return-void

    :cond_ok
    const/4 v1, 0x0

    invoke-virtual {v0, p1, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    return-void
.end method

.method public onBackPressed()V
    .locals 2

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/MainActivity;->web:Landroid/webkit/WebView;

    if-eqz v0, :cond_exit

    invoke-virtual {v0}, Landroid/webkit/WebView;->canGoBack()Z

    move-result v1

    if-eqz v1, :cond_exit

    invoke-virtual {v0}, Landroid/webkit/WebView;->goBack()V

    return-void

    :cond_exit
    invoke-super {p0}, Landroid/app/Activity;->onBackPressed()V

    return-void
.end method

.method protected onCreate(Landroid/os/Bundle;)V
    .locals 6

    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

    :try_start_0
    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
    move-result-object v0

    if-eqz v0, :cond_no_extra

    const-string v1, "url"

    invoke-virtual {v0, v1}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1

    iput-object v1, p0, Lcom/nishgamerrz/relaydash/MainActivity;->launchUrl:Ljava/lang/String;

    :cond_no_extra
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    goto :goto_intent_done

    :catch_0
    move-exception v0

    :goto_intent_done
    invoke-virtual {p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;
    move-result-object v0

    const/high16 v1, -0x1000000

    invoke-virtual {v0, v1}, Landroid/view/Window;->setStatusBarColor(I)V

    invoke-virtual {v0, v1}, Landroid/view/Window;->setNavigationBarColor(I)V

    new-instance v1, Landroid/webkit/WebView;

    invoke-direct {v1, p0}, Landroid/webkit/WebView;-><init>(Landroid/content/Context;)V

    iput-object v1, p0, Lcom/nishgamerrz/relaydash/MainActivity;->web:Landroid/webkit/WebView;

    invoke-virtual {v1}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;
    move-result-object v2

    const/4 v3, 0x1

    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setJavaScriptEnabled(Z)V

    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setDomStorageEnabled(Z)V

    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setDatabaseEnabled(Z)V

    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setAllowFileAccess(Z)V

    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setJavaScriptCanOpenWindowsAutomatically(Z)V

    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setSupportZoom(Z)V

    const/4 v3, 0x2

    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setCacheMode(I)V

    const/4 v3, 0x0

    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setBlockNetworkImage(Z)V

    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setMediaPlaybackRequiresUserGesture(Z)V

    new-instance v2, Lcom/nishgamerrz/relaydash/JsBridge;

    invoke-direct {v2, p0}, Lcom/nishgamerrz/relaydash/JsBridge;-><init>(Lcom/nishgamerrz/relaydash/MainActivity;)V

    iput-object v2, p0, Lcom/nishgamerrz/relaydash/MainActivity;->bridge:Lcom/nishgamerrz/relaydash/JsBridge;

    const-string v3, "And"

    invoke-virtual {v1, v2, v3}, Landroid/webkit/WebView;->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V

    new-instance v2, Landroid/webkit/WebChromeClient;

    invoke-direct {v2}, Landroid/webkit/WebChromeClient;-><init>()V

    invoke-virtual {v1, v2}, Landroid/webkit/WebView;->setWebChromeClient(Landroid/webkit/WebChromeClient;)V

    new-instance v2, Lcom/nishgamerrz/relaydash/MainActivity$1;

    invoke-direct {v2, p0}, Lcom/nishgamerrz/relaydash/MainActivity$1;-><init>(Lcom/nishgamerrz/relaydash/MainActivity;)V

    invoke-virtual {v1, v2}, Landroid/webkit/WebView;->setWebViewClient(Landroid/webkit/WebViewClient;)V

    const/high16 v2, -0x1000000

    invoke-virtual {v1, v2}, Landroid/webkit/WebView;->setBackgroundColor(I)V

    invoke-virtual {p0, v1}, Landroid/app/Activity;->setContentView(Landroid/view/View;)V

    const-string v2, "file:///android_asset/index.html"

    invoke-virtual {v1, v2}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V

    return-void
.end method

.method public getLaunchUrl()Ljava/lang/String;
    .locals 1

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/MainActivity;->launchUrl:Ljava/lang/String;

    return-object v0
.end method

.method protected onNewIntent(Landroid/content/Intent;)V
    .locals 2

    invoke-super {p0, p1}, Landroid/app/Activity;->onNewIntent(Landroid/content/Intent;)V

    if-eqz p1, :cond_done

    :try_start_0
    const-string v0, "url"

    invoke-virtual {p1, v0}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0

    iput-object v0, p0, Lcom/nishgamerrz/relaydash/MainActivity;->launchUrl:Ljava/lang/String;
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    goto :goto_loaded

    :catch_0
    move-exception v0

    :goto_loaded
    iget-object v1, p0, Lcom/nishgamerrz/relaydash/MainActivity;->web:Landroid/webkit/WebView;

    if-eqz v1, :cond_done

    const-string v1, "window.__relayConsumeLaunchUrl&&window.__relayConsumeLaunchUrl()"

    invoke-virtual {p0, v1}, Lcom/nishgamerrz/relaydash/MainActivity;->runJs(Ljava/lang/String;)V

    :cond_done
    return-void
.end method

.method protected onDestroy()V
    .locals 2

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/MainActivity;->web:Landroid/webkit/WebView;

    if-eqz v0, :cond_skip

    const/4 v1, 0x0

    invoke-virtual {v0, v1}, Landroid/webkit/WebView;->setWebViewClient(Landroid/webkit/WebViewClient;)V

    invoke-virtual {v0}, Landroid/webkit/WebView;->destroy()V

    :cond_skip
    invoke-super {p0}, Landroid/app/Activity;->onDestroy()V

    return-void
.end method
