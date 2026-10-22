# MYRA Laptop Agent — Setup (asaan steps)

Phone ki MYRA tumhare is Windows laptop ko control kar sakti hai: folder
banana, file delete/rename, browser kholna, scroll, type karna, volume,
tab badalna, screen ki tasveer dekhna, aur laptop band/restart karna —
sab awaaz se.

## Setup — sirf pehli dafa

**1. Laptop par Python install karo**
- https://www.python.org/downloads/ se Python download karke install karo.
- Install ke waqt neeche wali tick **"Add python to PATH" LAZMI lagana** —
  is ke baghair script nahi chalegi.

**2. Ye wala `laptop-agent` folder laptop par rakho**
- Poora folder jahan marzi rakho (Desktop par theek rahega). Files:
  `maira_agent.py`, `run_maira.bat`, ye `README.md`.

**3. `run_maira.bat` double-click karo**
- Pehli dafa zaroori packages (paho-mqtt, pyautogui, pillow) khud install
  honge — thora wait karna, internet chahiye.
- Phir console mein **pairing code** manga jayega (agla step dekho).
- "Broker se connect ho gaya" likha aaye to sab tayyar hai.

**4. Pairing code — dono taraf EK jaisa**
- Phone par MYRA app kholo: **Settings > KEYS & DATA > Laptop Pairing
  Code** — wahan ek lamba random code banao/likho (jaise 20+ characters
  ki apni pasand ki lambi si code-line) aur save karo.
- Laptop par agent pehli dafa wahi code mangega — **bilkul wahi code**
  likho. Code `maira_config.json` mein save ho jata hai, har dafa nahi
  likhna padega.
- ⚠️ Is code ko **password ki tarah rakho** — kisi ko mat do, kahin post
  mat karo. Jiske paas code hai, woh tumhare laptop ko command bhej
  sakta hai.

**5. Phone se test karo**
- MYRA se baat shuru karke kaho:
  **"Laptop par Desktop par ek folder banao, naam Test rakho"**
- Laptop ke Desktop par `Test` folder ban jaye to sab chal raha hai. 🎉
- Aur try karo: "laptop par Chrome kholo", "scroll karo", "volume
  barhao", "laptop ki screen par kya hai, dikhao", "Desktop par kya kya
  hai, batao".
- Shutdown/restart/delete se pehle MYRA hamesha pehle tumse confirm
  karegi — baghair tumhari "haan" ke nahi karegi.

## Roz ka istemal

- Jab laptop control chahiye, pehle `run_maira.bat` double-click karke
  agent chala lo (console window khuli rehne do — usi mein har command
  ka log dikhta hai).
- **Band karna:** console window band kar do (ya Ctrl+C dabao). Agent
  band = phone se koi control nahi.
- Laptop restart ho to agent khud nahi chalta — dobara double-click
  karna hoga. Agar chahte ho ke laptop on hote hi agent khud chal
  pare: `run_maira.bat` ki **shortcut** bana kar is folder mein rakho —
  `Win + R` dabao, `shell:startup` likho, Enter — khulne wale folder
  mein shortcut paste kar do.

## Naye Universal Commands

Awaaz se ye bhi keh sakte ho:

- **Drives:** "laptop par D drive kholo" — C, D, E waghera jo drive
  laptop par ho, khul jayegi (jo drive na ho, us par MYRA bata degi).
- **This PC:** "This PC kholo" — saari drives ek saath dekhne ke liye.
- **Folders:** Desktop, Documents, Downloads ke ilawa ab **Pictures,
  Music, Videos** bhi — "Pictures folder kholo".
- **Chrome tabs:** "naya tab kholo", "ye tab band karo", "agla tab",
  "pichla tab", "band hua tab wapas kholo" — pehle Chrome window khud
  aage aa jati hai, phir tab wala kaam hota hai.
- **Page scroll:** "neeche scroll karo" ke ilawa "page ke sab se
  neeche jao" / "sab se upar jao" bhi chalega.
- **Site kholna (Chrome pehle se khula ho):** "Chrome mein
  daraz.pk kholo" — address bar se seedha wahi site khul jayegi.
- **Page par dhoondhna:** "is page par 'price' dhoondo" — MYRA woh
  lafz page par dhoond kar wahan le jayegi.
- **Wallpaper:** "wallpaper badal do" (image Pictures/Desktop mein
  ho) — desktop ki tasveer badal jayegi.
- **Settings:** "Windows Settings kholo" ya "Control Panel kholo".

## Masla ho to

- **"Laptop se rabta nahi ho raha"** — check karo: (1) laptop par agent
  chala hua hai? (2) dono taraf internet on hai? (3) pairing code dono
  taraf **bilkul** ek jaisa hai (phone Settings aur laptop ki
  `maira_config.json`)? Code badalna ho to laptop par `maira_config.json`
  delete karke agent dobara chalao — code phir se manga jayega.
- **Python wala error** — Python dobara install karo aur "Add python to
  PATH" tick zaroor lagana.
- **Screenshot (capture_screen)** bara ho to agent khud quality kam
  karke bhejta hai — phone par tasveer aane mein chand second lag sakte
  hain.
