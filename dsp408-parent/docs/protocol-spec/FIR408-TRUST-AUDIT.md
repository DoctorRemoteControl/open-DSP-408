# FIR408 Library Trust Audit

Stand: 2026-07-16
Gerät der Live-Prüfung: `FIR408 V0113P`

## Sicherheitsregel im Controller

Beim FIR408 werden Speicherstellen nur noch als Gerätebestätigung verwendet,
wenn ihr Library-Status eindeutig `observed`, `confirmed` oder `verified`
enthält und nicht gleichzeitig als `predicted`, `inferred`, `derived`,
`partial`, `unknown`, `absent` oder `unsupported` gekennzeichnet ist.

Ein vollständiger Blockscan darf dadurch keine gesendeten Werte mehr mit
vermuteten Daten überschreiben. Die normale DSP408-Library bleibt von dieser
strikten FIR408-Regel unberührt.

## Schreibsperre für normale APIs

HTTP, Shell, Matrix und JSON-Presets laufen durch dieselbe zentrale
Schreibprüfung. Ein nicht bestätigter FIR408-Schreibpfad wird vor dem
Netzwerkversand abgebrochen. HTTP meldet dafür `409 unsupported_operation`;
Shell und Matrix melden `Unsupported`.

| Bereich | Normal freigegeben |
|---|---|
| Phase | InA–InD, Out1–Out8 |
| Output-PEQ | Out1–Out8 |
| Input-PEQ | InA und InD |
| Input-GEQ | keiner; auf FIR408 nicht vorhanden |
| Input-Gate | InA und InD |
| IIR-Crossover HP/LP | Out1–Out8 |
| Input-Kompressor | InA und InD |
| FIR/IIR-Verarbeitungsmodus | Out1–Out8 |
| FIR-Generator | Out1, Out2 und Out8 |
| externer FIR-Upload | InA, InB, Out1 und Out8 |

Beim Output-Crossover ist das aktuelle FIR408-Befehlsformat direkt auf Out1
und Out8 beobachtet. Die Ausgangsnummer ist linear und entspricht den bereits
bestätigten Gain-, Mute-, Matrix- und PEQ-Befehlen; deshalb bleiben alle acht
Ausgänge verfügbar. Dieselbe Endpunkt- und Kanalregel gilt für den einfachen
FIR/IIR-Modusbefehl. Input-Crossover wird gesperrt, weil dort bislang nur das
ältere, kürzere Payload-Format beobachtet wurde.

Der Raw-Befehl bleibt absichtlich verfügbar, um neue Protokollfelder
kontrolliert zu untersuchen. Er umgeht die Schreibprüfung und ist deshalb kein
normaler Fernsteuerungsweg.

Der direkte HTTP-Gain-Pfad wird außerdem nur noch als
`/channels/{channelId}/gain` erkannt. Dadurch überschreibt er nicht mehr die
verschachtelten PEQ- und GEQ-Gain-Endpunkte.

## Automatisch zugelassene Readbacks

| Bereich | Zugelassener Umfang | Grundlage |
|---|---:|---|
| Gain | InA–InD, Out1–Out8 | Live-Markerprüfung und kompletter 32-Block-Scan |
| Phase | InA–InD, Out1–Out8 | kanalweiser 0°/180°/0°-Marker mit drei vollständigen 32-Block-Dumps |
| Delay | alle 12 Kanäle | kontrollierte Write/Read-Differenzen |
| Matrix-Routing | Out1–Out8 | alle Ausgangsmasken beobachtet |
| Matrix-Crosspoint-Gain | 32 Crosspoints | vollständiger Dump-Diff |
| Output-Crossover | Out1–Out8 | Original-GUI-Werte gegen vollständigen Live-Dump geprüft; aktive Flanken und Bypass bestätigt |
| Output-PEQ | Out1–Out8 | alle Werte, Filtertypen und Bypass-Bits gegen Original-GUI und Live-Dump geprüft |
| Input-PEQ | InA–InD | alle Bänder und Bypass-Masken gegen Original-GUI und Live-Dump geprüft |
| Input-Gate | InA–InD | InA/InD per Marker-Diff, InB/InC per Original-GUI plus Live-Dump |
| Input-Kompressor | InA–InD | vollständige Records gegen die Original-GUI geprüft |
| Limiter-Parameter | Out1–Out8 | vollständiger Min/Max-Write/Read-Diff |
| Testtongenerator | Block-Readback | direkt beobachtet |
| Runtime-Meter | physischer Payload und dB-Kalibrierung | FIR408-LAN-Messreihe |
| Runtime-Limiterstatus | Out1–Out8 Bitmaske | Payload-/GUI-Prüfung |

## Nicht als Bestätigung zugelassene Readbacks

| Bereich | Grund | Verhalten |
|---|---|---|
| persistenter Mute-Readback | FIR408-Adresse nur vorhergesagt | TX-Zustand bleibt erhalten, Readback überschreibt ihn nicht |
| Input-GEQ | auf FIR408 laut Library nicht vorhanden | Decoder leer, Schreibzugriff gesperrt |
| historischer Output-Kompressor | falsche Alt-Hypothese; FIR408-Kompressor liegt auf InA–InD und die alten Adressen überlappen Output-PEQ | als `stale` markiert und nicht verwendet |
| Crossover Inputs | aus DSP408 oder Stride vorhergesagt | keine Gerätebestätigung |
| Flankensteilheit bei gebypasstem Crossover | der Rohwert speichert die in der GUI erinnerte Auswahl nicht zuverlässig | Frequenz und Bypass bestätigt; Steilheit bleibt `null` |
| Output-PEQ Filtertyp-Defaults | Library-Baseline nur vorhergesagt | nicht mehr als Fallback für Teilwrites benutzt |

## Meter-Vertrauen

Die Antwort auf `0x40` stammt nachweislich vom physischen DSP. Die
Raw-to-dB-Kalibrierung und die Limiter-Bitmaske sind bestätigt. Die Zuordnung
der zwölf Meter-Slots zu InA–Out8 ist in der Library weiterhin als
`predicted_from_dsp408` markiert, weil bei der letzten Live-Prüfung alle
Kanäle gleichzeitig gemutet waren.

Die HTTP-API unterscheidet deshalb:

- `levelConfirmedFromDevice`: echter Runtime-Payload empfangen
- `channelMappingConfirmed`: Slot wurde mit einem unterscheidbaren Signal verifiziert
- `levelCalibrationConfirmed`: Raw-to-dB-Modell am FIR408 gemessen
- `limiterMappingConfirmed`: Limiter-Bitmaske verifiziert

## Phase-Verifikation

Der Raider-Prüfer `Fir408PhaseReadVerifier` hat für jeden der zwölf Kanäle
separat `0° -> 180° -> 0°` geschrieben und nach jedem Zustand alle Blöcke
`0x00..0x1F` gelesen. Pro Kanal änderte sich genau ein Byte, die gespeicherten
`u16le`-Werte waren jeweils `0 -> 1 -> 0`, und jeder Wiederherstellungs-Dump
entsprach dem zugehörigen 0°-Dump bytegenau. Auch der vollständige Endzustand
entsprach dem Ausgangsdump.

Elf der zwölf aus der normalen DSP408-Library übernommenen Speicherstellen
waren für den FIR408 falsch; nur Out2 lag zufällig an derselben Position.
Die API gibt Phase und Delay nun zusammen mit ihren eigenen
`ConfirmedFromDevice`-, `Dirty`- und `lastUpdateSource`-Feldern aus.

## Gate-Verifikation

Der Raider-Prüfer `Fir408GateReadVerifier` hat für InA und InD jedes der vier
Gate-Felder einzeln auf einen weit entfernten Markerwert gesetzt und danach
sofort das vollständige Originaltupel wiederhergestellt. Vor und nach jedem
Schritt wurden alle Blöcke `0x00..0x1F` gelesen. Attack, Release und Hold
änderten jeweils beide Bytes des gespeicherten `u16le`-Werts; Threshold blieb
im gültigen Bereich unter 256 und änderte deshalb nur das Low-Byte.

Beobachtete Payload-Offsets:

| Eingang | Block | Attack | Release | Hold | Threshold |
|---|---:|---:|---:|---:|---:|
| InA | `00` | 29 | 31 | 33 | 35 |
| InD | `08` | 43 | 45 | 47 | 49 |

Alle acht Markerläufe wurden bytegenau zurückgesetzt; auch der vollständige
Enddump entsprach dem Ausgangsdump. Die vererbten InD-Positionen waren falsch.
Die anschließend abgelesenen Original-GUI-Werte von InB und InC stimmen
bytegenau mit den aus dem 138-Byte-Stride ermittelten Speicherstellen überein.
HTTP `state` liefert deshalb alle vier Eingänge als bestätigte `inputGates`.
Shell- und Matrix-`state` verwenden dieselben Core-Zustände.

## Nächste kontrollierte Prüfungen

1. Mit weiterhin gemuteten Ausgängen nacheinander einen unterscheidbaren
   Testpegel auf jeden Messpfad geben und die zwölf Meter-Slots bestätigen.
2. Input-Crossover einzeln bestätigen, bevor dessen normaler Schreibpfad
   freigegeben wird.
3. FIR-Dateiname, Tap-Anzahl und FIR-Bypass für die Eingänge mit einem
   kontrollierten Same-State-Vergleich eindeutig lokalisieren.
4. Bei gebypassten Crossovern prüfen, ob die in der GUI erinnerte Steilheit
   außerhalb des normalen Parameterdumps gespeichert wird.
