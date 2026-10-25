.class public Lcom/nishgamerrz/relaydash/JsBridge$3;
.super Ljava/lang/Object;
.source "JsBridge.java"

# interfaces
.implements Ljava/lang/Runnable;


# instance fields
.field private final activity:Lcom/nishgamerrz/relaydash/MainActivity;

.field private final text:Ljava/lang/String;


# direct methods
.method public constructor <init>(Lcom/nishgamerrz/relaydash/MainActivity;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    iput-object p1, p0, Lcom/nishgamerrz/relaydash/JsBridge$3;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    iput-object p2, p0, Lcom/nishgamerrz/relaydash/JsBridge$3;->text:Ljava/lang/String;

    return-void
.end method


# virtual methods
.method public run()V
    .locals 4

    :try_start_0
    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge$3;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    const-string v1, "clipboard"

    invoke-virtual {v0, v1}, Landroid/app/Activity;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;
    move-result-object v1

    check-cast v1, Landroid/content/ClipboardManager;

    iget-object v2, p0, Lcom/nishgamerrz/relaydash/JsBridge$3;->text:Ljava/lang/String;

    invoke-virtual {v1, v2}, Landroid/content/ClipboardManager;->setText(Ljava/lang/CharSequence;)V

    iget-object v0, p0, Lcom/nishgamerrz/relaydash/JsBridge$3;->activity:Lcom/nishgamerrz/relaydash/MainActivity;

    const-string v1, "Copied to clipboard"

    const/4 v2, 0x0

    invoke-static {v0, v1, v2}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;
    move-result-object v0

    invoke-virtual {v0}, Landroid/widget/Toast;->show()V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    goto :goto_done

    :catch_0
    move-exception v0

    goto :goto_done

    :goto_done
    return-void
.end method
