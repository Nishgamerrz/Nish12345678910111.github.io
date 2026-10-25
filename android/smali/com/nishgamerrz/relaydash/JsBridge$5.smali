.class public Lcom/nishgamerrz/relaydash/JsBridge$5;
.super Ljava/lang/Object;
.source "JsBridge.java"

# interfaces
.implements Ljava/lang/Runnable;


# instance fields
.field private final activity:Lcom/nishgamerrz/relaydash/MainActivity;

.field private final url:Ljava/lang/String;


# direct methods
.method public constructor <init>(Lcom/nishgamerrz/relaydash/MainActivity;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    iput-object p1, p0, Lcom/nishgamerrz/relaydash/JsBridge$5;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    iput-object p2, p0, Lcom/nishgamerrz/relaydash/JsBridge$5;->url:Ljava/lang/String;

    return-void
.end method


# virtual methods
.method public run()V
    .locals 12

    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
    move-result-wide v0

    const/4 v2, 0x0

    const/4 v3, -0x1

    :try_start_0
    new-instance v5, Ljava/net/URL;

    iget-object v4, p0, Lcom/nishgamerrz/relaydash/JsBridge$5;->url:Ljava/lang/String;

    invoke-direct {v5, v4}, Ljava/net/URL;-><init>(Ljava/lang/String;)V

    invoke-virtual {v5}, Ljava/net/URL;->openConnection()Ljava/net/URLConnection;
    move-result-object v5

    check-cast v5, Ljava/net/HttpURLConnection;

    const-string v4, "GET"

    invoke-virtual {v5, v4}, Ljava/net/HttpURLConnection;->setRequestMethod(Ljava/lang/String;)V

    const/16 v4, 0x1f40

    invoke-virtual {v5, v4}, Ljava/net/URLConnection;->setConnectTimeout(I)V

    invoke-virtual {v5, v4}, Ljava/net/URLConnection;->setReadTimeout(I)V

    invoke-virtual {v5}, Ljava/net/HttpURLConnection;->getResponseCode()I
    move-result v3

    invoke-virtual {v5}, Ljava/net/HttpURLConnection;->disconnect()V

    if-lez v3, :cond_neg

    const/4 v2, 0x1

    :cond_neg
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    goto :goto_done

    :catch_0
    move-exception v5

    const/4 v2, 0x0

    :goto_done
    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
    move-result-wide v9

    sub-long v9, v9, v0

    new-instance v6, Ljava/lang/StringBuilder;

    invoke-direct {v6}, Ljava/lang/StringBuilder;-><init>()V

    const-string v7, "window.__relayPing&&window.__relayPing("

    invoke-virtual {v6, v7}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v6, v2}, Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;

    const-string v7, ","

    invoke-virtual {v6, v7}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v6, v3}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    invoke-virtual {v6, v7}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v6, v9, v10}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;

    const-string v7, ")"

    invoke-virtual {v6, v7}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v6}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v6

    iget-object v7, p0, Lcom/nishgamerrz/relaydash/JsBridge$5;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    new-instance v8, Lcom/nishgamerrz/relaydash/JsBridge$6;

    invoke-direct {v8, v7, v6}, Lcom/nishgamerrz/relaydash/JsBridge$6;-><init>(Lcom/nishgamerrz/relaydash/MainActivity;Ljava/lang/String;)V

    invoke-virtual {v7, v8}, Landroid/app/Activity;->runOnUiThread(Ljava/lang/Runnable;)V

    return-void
.end method
