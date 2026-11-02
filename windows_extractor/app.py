import asyncio, csv, re, threading, queue
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from urllib.parse import urljoin
from datetime import datetime, timezone
import httpx
from bs4 import BeautifulSoup

SOURCES = {
    "Anuga": "https://www.anuga.com/anuga-exhibitors/list-of-exhibitors/index.php",
    "SIAL Paris": "https://www.sialparis.com/en/Exhibitors-2026",
    "THAIFEX Anuga Asia": "https://www.thaitradefair.com/fair-content/82/THAIFEX-Anuga%2BAsia%2B2026/2456/Exhibitor%2BList/",
    "Gulfood": "https://exhibitors.gulfood.com/gulfood-2026/Exhibitors",
    "WorldFood Istanbul": "https://worldfood-istanbul.com/en/exhibitor-list",
    "Summer Fancy Food Show": "https://events.specialtyfood.com/event/2026-summer-fancy-food-show/exhibitors/RXZlbnRWaWV3XzEyNTgzMTk%3D"
}

HEADERS = {"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) ZTOR-Fair-Extractor/0.2"}
EMAIL_RE = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.I)
PHONE_RE = re.compile(r"(?:(?:\+|00)\d{1,3}[\s.-]?)?(?:\(?\d{2,4}\)?[\s.-]?){2,5}\d{2,4}")
NAME_RE = re.compile(r"\b([A-ZÀ-ÖØ-Þ][a-zà-öø-ÿ'’-]+(?:\s+[A-ZÀ-ÖØ-Þ][a-zà-öø-ÿ'’-]+){1,3})\b")
ROLES = ["export manager","export director","head of export","commercial director","sales director",
         "international sales","business development","direttore commerciale","responsabile export","direttore export"]

def clean(s):
    return re.sub(r"\s+"," ",s or "").strip()

async def fetch(url):
    async with httpx.AsyncClient(headers=HEADERS, timeout=20, follow_redirects=True) as c:
        r=await c.get(url); r.raise_for_status(); return r.text

def parse_company_cards(html, base, fair):
    soup=BeautifulSoup(html,"lxml")
    out=[]; seen=set()
    nodes=soup.select("[class*='exhibitor'], [class*='company'], article, table tr, .result, li")
    for n in nodes:
        txt=clean(n.get_text(" ",strip=True))
        if len(txt)<4: continue
        a=n.find("a",href=True)
        h=n.find(["h2","h3","h4","strong"])
        company=clean(h.get_text(" ",strip=True)) if h else (clean(a.get_text(" ",strip=True)) if a else "")
        if not company or len(company)>150 or company in seen: continue
        if company.lower() in {"home","exhibitors","login","register","agenda","map"}: continue
        hall=stand=country=None
        m=re.search(r"Hall\s*([A-Za-z0-9.]+)",txt,re.I)
        if m: hall=m.group(1)
        m=re.search(r"(?:Stand|Booth)\s*(?:No\.?\s*)?([A-Za-z0-9./-]+)",txt,re.I)
        if m: stand=m.group(1)
        for c in ["Italy","France","Germany","Spain","Portugal","Greece","United Kingdom","United States",
                  "Canada","Brazil","Turkey","China","Japan","Thailand","India","Netherlands","Belgium",
                  "Poland","UAE","United Arab Emirates","Saudi Arabia","South Korea","Australia"]:
            if c.lower() in txt.lower(): country=c; break
        seen.add(company)
        out.append({
            "fair_name":fair,"company_name":company,"country":country,"hall":hall,"stand":stand,
            "category":None,"product":None,"brand":None,"company_website":None,
            "exhibitor_profile_url":urljoin(base,a["href"]) if a else None,
            "public_email":None,"public_phone":None,"contact_name":None,"contact_title":None,
            "contact_source":None,"source_url":base,"confidence":0.55,
            "extracted_at":datetime.now(timezone.utc).isoformat()
        })
    return out

async def enrich_profile(r):
    u=r.get("exhibitor_profile_url")
    if not u: return r
    try: html=await fetch(u)
    except: return r
    soup=BeautifulSoup(html,"lxml")
    for x in soup(["script","style","noscript"]): x.decompose()
    txt=clean(soup.get_text(" ",strip=True)); low=txt.lower()
    es=EMAIL_RE.findall(txt); ps=PHONE_RE.findall(txt)
    if es: r["public_email"]=es[0]
    if ps: r["public_phone"]=ps[0]
    for a in soup.find_all("a",href=True):
        href=a["href"]; lab=clean(a.get_text(" ",strip=True)).lower()
        if href.startswith("http") and any(k in lab for k in ["website","web","site","sito","homepage"]):
            r["company_website"]=href; break
    for fld,keys in {"category":["category","categoria","sector","settore"],
                     "product":["products","product","prodotti","prodotto"],
                     "brand":["brands","brand","marchi","marchio"]}.items():
        if r.get(fld): continue
        for k in keys:
            i=low.find(k)
            if i>=0: r[fld]=txt[i:i+220]; break
    r["confidence"]=min(.99,r["confidence"]+.08)
    return r

async def enrich_company(r):
    u=r.get("company_website")
    if not u: return r
    try: html=await fetch(u)
    except: return r
    soup=BeautifulSoup(html,"lxml")
    urls=[u]
    for a in soup.find_all("a",href=True):
        href=a["href"]; lab=(a.get_text(" ",strip=True) or "").lower()
        if any(k in href.lower() or k in lab for k in ["contact","contatti","team","management","sales","export","commercial","about","chi-siamo"]):
            v=urljoin(u,href)
            if v not in urls: urls.append(v)
    for v in urls[:6]:
        try: h=await fetch(v)
        except: continue
        s=BeautifulSoup(h,"lxml")
        for x in s(["script","style","noscript"]): x.decompose()
        txt=clean(s.get_text(" ",strip=True)); low=txt.lower()
        if not r.get("public_email"):
            es=EMAIL_RE.findall(txt)
            if es: r["public_email"]=es[0]
        if not r.get("public_phone"):
            ps=PHONE_RE.findall(txt)
            if ps: r["public_phone"]=ps[0]
        if not r.get("contact_name"):
            for role in ROLES:
                i=low.find(role)
                if i>=0:
                    w=txt[max(0,i-160):i+len(role)+160]
                    names=NAME_RE.findall(w)
                    if names:
                        r["contact_name"]=names[0]; r["contact_title"]=role; r["contact_source"]=v
                        r["confidence"]=min(.99,r["confidence"]+.08)
                        break
    return r

async def scrape_fair(fair,url,log):
    log(f"Scarico {fair}...")
    try: html=await fetch(url)
    except Exception as e:
        log(f"ERRORE {fair}: {e}"); return []
    rows=parse_company_cards(html,url,fair)
    log(f"{fair}: {len(rows)} candidati trovati")
    enriched=[]
    for i,r in enumerate(rows[:500]):
        try: r=await enrich_profile(r)
        except: pass
        try: r=await enrich_company(r)
        except: pass
        enriched.append(r)
        if (i+1)%25==0: log(f"{fair}: elaborati {i+1}/{min(len(rows),500)}")
    return enriched

class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("ZTOR Exhibitor Intelligence Extractor")
        self.geometry("980x700")
        self.rows=[]
        self.q=queue.Queue()
        self.vars={}
        top=ttk.Frame(self,padding=12); top.pack(fill="x")
        ttk.Label(top,text="ZTOR EXHIBITOR INTELLIGENCE",font=("Segoe UI",18,"bold")).pack(anchor="w")
        ttk.Label(top,text="Seleziona le fiere e genera un database aziende strutturato").pack(anchor="w",pady=(0,10))
        box=ttk.LabelFrame(self,text="Fiere",padding=10); box.pack(fill="x",padx=12)
        for i,name in enumerate(SOURCES):
            v=tk.BooleanVar(value=True); self.vars[name]=v
            ttk.Checkbutton(box,text=name,variable=v).grid(row=i//2,column=i%2,sticky="w",padx=8,pady=4)
        actions=ttk.Frame(self,padding=12); actions.pack(fill="x")
        self.runbtn=ttk.Button(actions,text="AVVIA ESTRAZIONE",command=self.start); self.runbtn.pack(side="left")
        ttk.Button(actions,text="ESPORTA CSV",command=self.export).pack(side="left",padx=8)
        self.status=ttk.Label(actions,text="Pronto"); self.status.pack(side="right")
        self.tree=ttk.Treeview(self,columns=("fair","company","country","product","contact"),show="headings",height=14)
        for c,t,w in [("fair","Fiera",140),("company","Azienda",260),("country","Paese",100),
                      ("product","Prodotto/Categoria",220),("contact","Referente",180)]:
            self.tree.heading(c,text=t); self.tree.column(c,width=w)
        self.tree.pack(fill="both",expand=True,padx=12)
        self.logbox=tk.Text(self,height=10); self.logbox.pack(fill="both",padx=12,pady=12)
        self.after(200,self.poll)

    def log(self,msg): self.q.put(("log",msg))
    def poll(self):
        try:
            while True:
                kind,data=self.q.get_nowait()
                if kind=="log":
                    self.logbox.insert("end",data+"\n"); self.logbox.see("end")
                elif kind=="done":
                    self.rows=data; self.runbtn.config(state="normal")
                    self.status.config(text=f"{len(self.rows)} aziende")
                    for x in self.tree.get_children(): self.tree.delete(x)
                    for r in self.rows[:1000]:
                        self.tree.insert("", "end", values=(r.get("fair_name"),r.get("company_name"),r.get("country"),
                            r.get("product") or r.get("category") or "", r.get("contact_name") or ""))
        except queue.Empty: pass
        self.after(200,self.poll)

    def start(self):
        fairs=[n for n,v in self.vars.items() if v.get()]
        if not fairs: messagebox.showwarning("ZTOR","Seleziona almeno una fiera"); return
        self.runbtn.config(state="disabled"); self.status.config(text="Estrazione in corso...")
        threading.Thread(target=self.worker,args=(fairs,),daemon=True).start()

    def worker(self,fairs):
        async def go():
            allr=[]
            for n in fairs: allr.extend(await scrape_fair(n,SOURCES[n],self.log))
            # simple dedup by fair+company
            seen=set(); out=[]
            for r in allr:
                k=(r["fair_name"].lower(),r["company_name"].lower())
                if k not in seen: seen.add(k); out.append(r)
            return out
        try: rows=asyncio.run(go())
        except Exception as e:
            self.log(f"Errore generale: {e}"); rows=[]
        self.q.put(("done",rows))

    def export(self):
        if not self.rows:
            messagebox.showinfo("ZTOR","Nessun dato da esportare"); return
        p=filedialog.asksaveasfilename(defaultextension=".csv",filetypes=[("CSV","*.csv")],initialfile="ztor_exhibitors.csv")
        if not p: return
        fields=list(self.rows[0].keys())
        with open(p,"w",newline="",encoding="utf-8-sig") as f:
            w=csv.DictWriter(f,fieldnames=fields); w.writeheader(); w.writerows(self.rows)
        messagebox.showinfo("ZTOR",f"Esportate {len(self.rows)} aziende")

if __name__=="__main__":
    App().mainloop()
