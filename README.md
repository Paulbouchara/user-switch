# User Switch

Change de profil utilisateur avec une séquence de boutons physiques, sans root. Conçu pour GrapheneOS, fonctionne aussi sur un Pixel sous Android d'origine (voir [Compatibilité testée](#compatibilité-testée)).

## Fonctionnement

- **Programme de fond** (`src/dev/userswitch/daemon/Main.java`) : il est lancé avec les droits shell d'ADB via `app_process` et survit à la coupure d'ADB. Il lit les boutons dans `/dev/input` (power, volume haut, volume bas), découpe les appuis en séquences (`UP UP DOWN:long`, accords `UP+DOWN`), envoie chaque séquence de 3 points ou plus à l'app par binder, puis change de profil si l'app renvoie une cible.
- **App** (`MainActivity`, `ConfigProvider`, `Store`) : elle enregistre les séquences et les associe à un profil ou à « Profil suivant ». Le découpage étant fait par le programme de fond, une séquence enregistrée est toujours découpée comme celle qu'on refait ensuite.

Langues : anglais (par défaut) et français (`res/values-fr/`). L'app suit la langue du système, et se règle à part dans Paramètres › Applis › User Switch › Langue (Android 13+, via `res/xml/locales_config.xml`). Pour ajouter une langue : un `res/values-<code>/strings.xml` et une ligne dans `locales_config.xml`.

Score d'une séquence : 1 point par appui, +1 pour un appui long (≥ 500 ms), +1 pour plusieurs boutons pressés ensemble. Une séquence se termine après 800 ms sans appui.

Seuls les vrais profils sont proposés comme cibles : un profil de travail ou l'Espace privé d'Android d'origine (`FLAG_PROFILE`) ne peut pas être ouvert par `switchUser` et n'apparaît pas.

## Interface

Material 3 sans bibliothèque : le projet se compile sans Gradle, donc pas de Material Components. `M3.java` dessine les formes, les états pressés, l'échelle typographique et les composants (cartes, boutons, puces) avec les vues du système.

- **Couleurs Material You**, sur trois niveaux, en clair et en sombre :
  - Android 14+ (`values-v34`) : les rôles Material 3 du système (`system_surface_container_*`, `system_primary_*`…) ;
  - Android 12–13 (`values-v31`) : les palettes du fond d'écran (`system_accent*`, `system_neutral*`), tons choisis à la main ;
  - Android 11 (`values`) : la palette de base Material 3 (violet), faute de couleurs dynamiques.
- **Icône** adaptative aux couleurs du fond d'écran (Android 12+), avec une couche monochrome pour les icônes à thème.
- **Écran** : carte d'état (programme de fond, Shizuku), bannière d'enregistrement avec compte à rebours, séquences en liste groupée avec les touches en puces, encart « Sans Shizuku » repliable avec bouton Copier, bouton flottant « Enregistrer ». Le contenu passe sous les barres système, avec un fond derrière la barre d'état et un voile derrière une navigation à 3 boutons.
- **Guide Shizuku** en 6 étapes, avec un bouton par étape (Play Store, À propos du téléphone, options développeur, Shizuku, autorisation). Il suit l'avancement et se replie dès que le programme de fond tourne. Une fois Shizuku associé, un Shizuku arrêté renvoie directement à l'étape « Démarre Shizuku ».
- **Pliables et tablettes** : une colonne en dessous de 600 dp de large, deux panneaux au-dessus (état et guide à gauche, séquences à droite). Plier ou déplier réorganise l'écran sans recréer l'activité (`configChanges`). Le conseil pour l'association Shizuku s'adapte : Fold ouvert, écran partagé ; Fold fermé, code saisi dans la notification de Shizuku.

## Durcissement

`Hardening.java`. Partout, l'app :

- demande le marquage mémoire (MTE) en mode synchrone (`android:memtagMode="sync"`), appliqué là où le matériel et le système l'activent ;
- masque les fenêtres en surimpression au-dessus d'elle (`setHideOverlayWindows`) ;
- ignore les touchers qui passent à travers une autre fenêtre (`setFilterTouchesWhenObscured`), puisqu'elle accorde l'autorisation Shizuku.

Sur GrapheneOS, une carte « Durcissement GrapheneOS » liste les réglages par app que seul l'utilisateur peut activer (marquage mémoire, mode de compatibilité désactivé, chargement dynamique de code restreint, autorisation Capteurs refusée) et ouvre la page de l'app dans les Paramètres. GrapheneOS est reconnu à ses applis système (`app.grapheneos.apps`, `app.grapheneos.info`) : ses builds reproductibles portent un utilisateur et un hôte génériques, inutilisables pour le détecter.

## Avec Shizuku (sans PC)

L'app lance le programme de fond comme UserService Shizuku (`DaemonService`, droits shell). Le guide intégré à l'app reprend ces étapes.

1. Installe Shizuku, démarre-le (débogage sans fil), puis ouvre User Switch et accorde l'autorisation.
2. **Après un redémarrage** : sans root, Shizuku ne démarre pas tout seul. Une notification le rappelle (bouton « Ouvrir Shizuku »). Une fois Shizuku démarré, le programme de fond repart **tout seul, en général en 1 à 2 minutes** (parfois plus : Android espace les alarmes des apps rarement ouvertes), sans ouvrir l'app ; toucher la notification le relance tout de suite.

   Pourquoi ce délai : Shizuku n'envoie son binder qu'à sa propre app au démarrage, et aux autres apps autorisées seulement quand leur processus change d'état. `ShizukuWatch` (une alarme non réveillante, toutes les 60 s, armée au démarrage et quand Shizuku s'arrête) provoque ce changement d'état. Le système le provoque aussi de lui-même environ 90 s après. L'alarme n'est armée que si le programme de fond a déjà tourné une fois via Shizuku, et pas si tu l'as arrêté toi-même. Elle se désarme dès qu'il tourne, et abandonne au bout de 6 h ou après 3 lancements sans connexion (programme de fond qui plante).

Le programme de fond lancé par Shizuku survit au changement de profil. Un seul programme de fond tourne à la fois : celui qui démarre arrête l'autre (fichier `/data/local/tmp/userswitch.pid`).

**Si le framework redémarre** (plantage de `system_server`, sans redémarrage du téléphone) : le programme de fond survit, et reprend sa connexion au nouveau `system_server` (`activityManager()` redemande le service `activity` quand le binder est mort). Constaté sur Android 17 bêta : aucun signal de vie perdu, l'app réveillée dans les 30 s. Shizuku, lui, reste lié à l'ancien `system_server` : il faut le redémarrer dans son app, et le guide pointe alors l'étape 5.

## Compiler et déployer

Prérequis : JDK 17 ou plus, build-tools 36, `platforms/android-36` dans `~/Android/Sdk`. Les bibliothèques client Shizuku 13.1.5 (Maven Central `dev.rikka.shizuku:{api,provider,shared,aidl}`) sont dans `libs/`.

```sh
./build.sh              # → build/userswitch.apk
adb tcpip 5555          # une fois par démarrage du téléphone : contrairement au débogage sans fil, ce mode survit au changement de profil
scripts/deploy.sh       # installe l'app pour le profil 0 et (re)lance le programme de fond
scripts/deploy.sh --start   # relance seulement le programme de fond (après un redémarrage du téléphone)
```

`scripts/phone.sh` choisit d'abord un téléphone branché en USB ; sinon il retrouve le téléphone sur le réseau, dont l'IP change à chaque reconnexion Wi-Fi.
Journal du programme de fond : `adb shell cat /data/local/tmp/userswitch.log`.

## Compatibilité testée

| Appareil | Système | Vérifié |
|---|---|---|
| Pixel 10 Pro XL | GrapheneOS, Android 17 | boutons, enregistrement, changement de profil (aussi écran éteint), Shizuku, interface, carte de durcissement |
| Pixel 11 Pro Fold | Android 17 bêta d'origine | boutons, aller-retour de profil (1 ms par changement), Shizuku, interface pliée et dépliée, reprise après plantage du framework |
| Émulateur `small_phone` | Android 11 | interface, palette de base, voile de navigation à 3 boutons |
| Émulateur `pixel_tablet` | Android 12 | interface en deux panneaux, couleurs du fond d'écran |
| Émulateur `pixel_fold` | Android 14 | rôles Material 3 du système, pliage et dépliage sans recréer l'activité |

Sur émulateur, seule l'interface est vérifiée : ni boutons physiques, ni programme de fond.

## Limites actuelles

- Après un redémarrage, il faut démarrer Shizuku à la main (pas de démarrage automatique de Shizuku sans root) ; le programme de fond suit en 1 à 2 minutes.
- Les appuis ne sont pas interceptés : le volume change quand même pendant la séquence.
- La séquence refaite doit être identique à celle enregistrée.
- **Android 17 bêta d'origine** (Pixel 11 Pro Fold, DP11) : au retour vers le profil principal, System UI plante à chaque fois (`ElementKey(screenrecord)`, puis `controls`, « was composed 2 times » dans les réglages rapides). Parfois `system_server` suit, faute d'écran de verrouillage en 20 s, et le framework redémarre. C'est un bug de la bêta : le changement de profil est le même que celui des Paramètres. Retirer une tuile ne fait que déplacer le plantage.
- Sur cette même bêta, `development_settings_enabled` et `adb_wifi_enabled` valent 0 pour l'app même quand ils sont activés : le guide ne se fie qu'à une valeur 1.
- Sur le Pixel 11 Pro Fold, trois périphériques déclarent les touches (`gpio_keys`, le capteur d'empreinte du bouton power, `uinput_nav`). Seul `gpio_keys` envoie des appuis aujourd'hui ; si un autre doublait un appui, la séquence en cours serait abandonnée.
- Juste après l'ouverture de l'app, l'état du programme de fond reste « en attente » jusqu'au signal de vie suivant (30 s au plus).
