.class public Lcom/nishgamerrz/relaydash/JsBridge$4;
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

    iput-object p1, p0, Lcom/nishgamerrz/relaydash/JsBridge$4;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    iput-object p2, p0, Lcom/nishgamerrz/relaydash/JsBridge$4;->url:Ljava/lang/String;

    return-void
.end method


# virtual methods
.method public run()V
    .locals 4

    :try_start_0
    new-instance v0, Landroid/content/Intent;

    const-string v1, "android.intent.action.VIEW"

    iget-object v2, p0, Lcom/nishgamerrz/relaydash/JsBridge$4;->url:Ljava/lang/String;

    invoke-static {v2}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;
    move-result-object v2

    invoke-direct {v0, v1, v2}, Landroid/content/Intent;-><init>(Ljava/lang/String;Landroid/net/Uri;)V

    const/high16 v1, 0x10000000

    invoke-virtual {v0, v1}, Landroid/content/Intent;->addFlags(I)Landroid/content/Intent;

    iget-object v1, p0, Lcom/nishgamerrz/relaydash/JsBridge$4;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    invoke-virtual {v1, v0}, Landroid/app/Activity;->startActivity(Landroid/content/Intent;)V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    goto :goto_done

    :catch_0
    move-exception v0

    iget-object v1, p0, Lcom/nishgamerrz/relaydash/JsBridge$4;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    const-string v2, "No app can open that link"

    const/4 v3, 0x0

    invoke-static {v1, v2, v3}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;
    move-result-object v1

    invoke-virtual {v1}, Landroid/widget/Toast;->show()V

    :goto_done
    return-void
.end method
