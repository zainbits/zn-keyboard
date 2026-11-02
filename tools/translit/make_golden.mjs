// Writes tools/translit/golden.tsv from the reference Lekhani runner (plain JS, no WebAssembly SIMD), for
// verify_lekhani.sh to compare LekhaniTransliterator against. translit.js is not in the repo; fetch it with
//   curl -sSLO https://huggingface.co/udgamlabs/Lekhani/resolve/main/translit.js
// then run: node tools/translit/make_golden.mjs translit.js   (don't commit translit.js)
// Each line is word<TAB>top 4 candidates joined by spaces, duplicates and empty strings dropped, as
// LekhaniTransliterator.candidates returns them.
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const runner = process.argv[2];
if (!runner) {
  console.error("usage: node tools/translit/make_golden.mjs /path/to/translit.js [out.tsv]");
  process.exit(2);
}
const here = dirname(fileURLToPath(import.meta.url));
const assets = join(here, "../../app/src/main/assets/translit");
const out = process.argv[3] || join(here, "golden.tsv");

// translit.js is an ES module with a .js name; load it from a data: URL so no package.json is needed
const src = readFileSync(resolve(runner), "utf8");
const { Transliterator } = await import("data:text/javascript;base64," + Buffer.from(src).toString("base64"));
const r = (f) => new Uint8Array(readFileSync(join(assets, f)));
const tr = await Transliterator.load({
  model: r("model.q4"), json: r("model.json"), words: r("words.bin"),
  rerank: JSON.parse(readFileSync(join(assets, "rerank.json"), "utf8")), simd: false,
});
if (tr.simd) throw new Error("expected the plain-JS kernels");

const WORDS = `
a b c d e h i k m n o s t u y z aa ee oo
kaise ho kya hai haan nahi nahin na mat bhi to toh ki ka ke ko se me mein main mai hum tum aap
yaar bhai behen didi bhaiya dost acha accha achha theek thik sahi galat bilkul shayad zaroor jaldi
kal aaj parso abhi kabhi phir fir baad pehle subah shaam raat din saal mahina hafta waqt samay
kaha kahan yahan wahan idhar udhar upar neeche andar bahar paas door saath bina liye kyun kyunki
kaun kitna kitne kitni jab tab agar lekin magar par aur ya ki kyonki isliye matlab
khana pani chai doodh roti sabzi daal chawal paratha samosa mithai namak cheeni
ghar school college office kaam naukri paisa rupaye dukaan bazaar sheher gaon desh duniya
pyaar mohabbat ishq dil zindagi khushi dukh dard sapna yaad intezaar dhanyavaad shukriya maaf
bahut zyada thoda kam bada chhota naya purana accha bura sundar khoobsurat garam thanda
jao aao chalo ruko suno dekho bolo karo likho padho socho samjho batao bataiye dijiye lijiye
karna jana aana dena lena khelna sona uthna baithna chalna rona hasna gaana nachna
gaya gayi gaye aaya aayi raha rahi rahe hoga hogi honge tha thi the hua hui hue
kar raha hoon hain hoon kr rha h nhi kyu kese kaisa kaisi sab kuch koi kisi kuchh
ram shyam sita gita rahul priya amit anjali vikas neha rohan pooja arjun krishna shiva ganesh
mohammad zain ali fatima ayesha imran salman shahrukh aamir sachin virat dhoni modi gandhi
delhi mumbai bangalore kolkata chennai hyderabad pune lucknow jaipur patna bharat hindustan
computer mobile phone internet laptop email message video photo camera police doctor hospital
train bus car ticket station market hotel restaurant cricket football movie film music party
birthday happy sorry thanks please okay ok hello hi bye good morning night
whatsapp facebook instagram google youtube online offline download update password
namaste namaskar pranam alvida swagat badhai mubarak janamdin diwali holi eid
bhagwan mandir masjid gurudwara puja aarti prasad
vidyalaya vishwavidyalaya pradhanmantri mukhyamantri rashtrapati sanskriti swatantrata
antarrashtriya prayogshala vigyan ganit itihaas bhugol sahitya kavita kahani upanyas
mujhe tujhe usse unse humse tumse aapse inhe unhe hamara tumhara aapka mera tera uska unka
chahiye chahte chahta chahti sakta sakti sakte pata malum lagta lagti lagte
arrey arre yaarrr bhaaai haaan nahiii acchhaaa ohh hmm hmmm ummm
xyz qwerty zzz ksh gya shr tra jna NAMASTE Kaise hello123 café
aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa bahutbahutbahutbahutbahutbahutbahutbahutbahutbahutbahutbahutbahutbahut
`.trim().split(/\s+/);

const seen = new Set(), lines = [];
for (const w of WORDS) {
  if (seen.has(w)) continue;
  seen.add(w);
  const cands = [...new Set(tr.word(w).candidates.map((c) => c.text).filter(Boolean))].slice(0, 4);
  lines.push(`${w}\t${cands.join(" ")}`);
}
writeFileSync(out, lines.join("\n") + "\n");
console.log(`${lines.length} words -> ${out}`);
