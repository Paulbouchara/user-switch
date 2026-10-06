# User Switch

Change de profil utilisateur sur GrapheneOS avec une séquence de boutons physiques, sans root.

## Fonctionnement

- **Programme de fond** (`src/dev/userswitch/daemon/Main.java`) : il est lancé avec les droits shell d'ADB via `app_process` et survit à la coupure d'ADB. Il lit les boutons dans `/dev/input` (power, volume haut, volume bas), découpe les appuis en séquences (`UP UP DOWN:long`, accords `UP+DOWN`), envoie chaque séquence de 3 points ou plus à l'app par binder, puis change de profil si l'app renvoie une cible.
- **App** (`MainActivity`, `ConfigProvider`, `Store`) : elle enregistre les séquences et les associe à un profil ou à « Profil suivant ». Le découpage étant fait par le programme de fond, une séquence enregistrée est toujours découpée comme celle qu'on refait ensuite.

Score d'une séquence : 1 point par appui, +1 pour un appui long (≥ 500 ms), +1 pour plusieurs boutons pressés ensemble. Une séquence se termine après 800 ms sans appui.

## Avec Shizuku (sans PC)

L'app lance le programme de fond comme UserService Shizuku (`DaemonService`, droits shell).

1. Installe Shizuku, démarre-le (débogage sans fil), puis ouvre User Switch et accorde l'autorisation.
2. **Après un redémarrage** : sans root, Shizuku ne démarre pas tout seul. Une notification le rappelle (bouton « Ouvrir Shizuku »). Une fois Shizuku démarré, le programme de fond repart **tout seul en 1 à 2 minutes**, sans ouvrir l'app ; toucher la notification le relance tout de suite.

   Pourquoi ce délai : Shizuku n'envoie son binder qu'à sa propre app au démarrage, et aux autres apps autorisées seulement quand leur processus change d'état. `ShizukuWatch` (une alarme non réveillante, toutes les 60 s, armée au démarrage et quand Shizuku s'arrête) provoque ce changement d'état. Le système le provoque aussi de lui-même environ 90 s après. L'alarme se désarme dès que le programme de fond tourne, et abandonne au bout de 6 h.

Le programme de fond lancé par Shizuku survit au changement de profil. Un seul programme de fond tourne à la fois : celui qui démarre arrête l'autre (fichier `/data/local/tmp/userswitch.pid`).

## Compiler et déployer

Prérequis : JDK 17 ou plus, build-tools 36, `platforms/android-36` dans `~/Android/Sdk`. Les bibliothèques client Shizuku 13.1.5 (Maven Central `dev.rikka.shizuku:{api,provider,shared,aidl}`) sont dans `libs/`.

```sh
./build.sh              # → build/userswitch.apk
adb tcpip 5555          # une fois par démarrage du téléphone : contrairement au débogage sans fil, ce mode survit au changement de profil
scripts/deploy.sh       # installe l'app pour le profil 0 et (re)lance le programme de fond
scripts/deploy.sh --start   # relance seulement le programme de fond (après un redémarrage du téléphone)
```

`scripts/phone.sh` retrouve le téléphone sur le réseau : son IP change à chaque reconnexion Wi-Fi.
Journal du programme de fond : `adb shell cat /data/local/tmp/userswitch.log`.

## Limites actuelles

- Après un redémarrage, il faut démarrer Shizuku à la main (pas de démarrage automatique de Shizuku sans root) ; le programme de fond suit en 1 à 2 minutes.
- Les appuis ne sont pas interceptés : le volume change quand même pendant la séquence.
- La séquence refaite doit être identique à celle enregistrée.
