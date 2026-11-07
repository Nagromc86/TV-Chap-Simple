# TV Chap Simple

**Télécommande universelle pour TV Samsung — lance tes apps en un bouton.**

Prototype d'application Android : quelques gros boutons pour piloter une TV Samsung
(Tizen 2016+) sur le réseau local, et surtout **ouvrir directement une application**
du téléviseur (TV Streamy, TV by Free, etc.) sans naviguer dans les menus.

> ⚠️ **Prototype.** Pensé pour une utilisation perso, en sideload. À tester sur
> l'appareil : le pilotage se fait sur le réseau local, il n'y a rien à simuler ici.

## Fonctions

- **Allumer** (Wake-on-LAN) / **Éteindre** (API)
- **Lancer une application** par son identifiant (ex. TV Streamy `TvStrmy001.TVStreamy`)
- **Art Mode** (Frame) · **Volume** · navigation (OK, flèches, retour, accueil)
- **Détection des applis installées** sur la TV pour associer les boutons

## Comment ça marche

Les TV Samsung exposent sur le LAN :
- un **canal télécommande WebSocket** `wss://<ip>:8002/api/v2/channels/samsung.remote.control` (touches) ;
- une **API applications** `http://<ip>:8001/api/v2/applications/<appId>` (lancer une app) ;
- `ed.installedApp.get` pour **lister les apps installées** ;
- le canal `com.samsung.art-app` pour l'**Art Mode**.

L'allumage utilise un **paquet magique Wake-on-LAN** (UDP). Ces échanges se font
dans un **plugin natif** (`SamsungRemotePlugin.java`, OkHttp), car ils ne passent
pas proprement depuis la couche web (cert auto-signé, contenu mixte).

## Mise en route (sur le téléphone)

1. Téléphone et TV sur le **même Wi-Fi**.
2. Réglages ⚙️ → saisir l'**adresse IP** de la TV → **Appairer** (la TV affiche « Autoriser »).
3. Pour l'allumage : activer **« Mise sous tension à distance / Wake-on-LAN »** dans
   les réglages réseau de la TV. L'adresse MAC est récupérée automatiquement à l'appairage.
4. « Détecter les applis » puis associer les boutons.

## Build

GitHub Actions compile un **APK release signé** à chaque push sur `main` et le publie
dans la release `latest` (+ artefact). L'app se met à jour depuis ses réglages.

```bash
npm install
npx cap sync android
cd android && ./gradlew assembleRelease
```
