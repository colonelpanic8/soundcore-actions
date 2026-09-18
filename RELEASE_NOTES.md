Make AI translation gestures work while Soundcore is in the background.

- Dispatch the Liberty 5 Pro AI translation callback directly from the Bluetooth listener, the way Anka callbacks already were. A physical gesture test showed the earbud packet always arrives, but with no Soundcore activity alive the vendor opens no screen, so component substitution alone never ran the mapping.
- Run the **Real-time translation** mapping for that packet, falling back to **Face-to-face translation** when only that one is mapped, because the packet does not name a screen.
- Keep the ongoing **Earbud actions** notification alive for translation mappings too, not only for Anka.
- Debounce Anka and translation gestures independently so a gesture and the vendor screen it opens still run the mapping once.
