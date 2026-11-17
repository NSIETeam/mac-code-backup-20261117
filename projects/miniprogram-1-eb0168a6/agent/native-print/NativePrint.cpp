// Windows PDF rendering and GDI printing, using the logged-in user's driver settings.
// Compatible with the narrowly defined arguments emitted by Siyue 2.1.1.
#define NOMINMAX
#include <windows.h>
#include <winspool.h>
#include <wtsapi32.h>
#include <userenv.h>
#include <sddl.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Storage.h>
#include <winrt/Windows.Storage.Streams.h>
#include <winrt/Windows.Data.Pdf.h>
#include <winrt/Windows.Graphics.Imaging.h>
#include <filesystem>
#include <vector>
#include <string>
#include <iostream>
#include <algorithm>
#include <fstream>
#include <wincrypt.h>
#include <memory>
#include "fpdfview.h"
#pragma comment(lib,"windowsapp.lib")
#pragma comment(lib,"winspool.lib")
#pragma comment(lib,"wtsapi32.lib")
#pragma comment(lib,"userenv.lib")
#pragma comment(lib,"advapi32.lib")
#pragma comment(lib,"gdi32.lib")
#pragma comment(lib,"ole32.lib")
namespace fs=std::filesystem;
using namespace winrt;
using namespace Windows::Storage;
using namespace Windows::Storage::Streams;
using namespace Windows::Data::Pdf;
using namespace Windows::Graphics::Imaging;
struct Handle { HANDLE h=nullptr; ~Handle(){if(h&&h!=INVALID_HANDLE_VALUE)CloseHandle(h);} operator HANDLE()const{return h;} };
struct Job {std::wstring queue,file,name;int copies=1;bool duplex=false,checkOnly=false,compat=false;};
static const wchar_t* phase=L"arguments";
static void require(bool value,DWORD code=ERROR_INVALID_DATA){if(!value)throw hresult_error(HRESULT_FROM_WIN32(code?code:ERROR_GEN_FAILURE));}
static void check(BOOL value){if(!value)throw_last_error();}
static std::wstring quote(const std::wstring& s){
 std::wstring r=L"\"";size_t slashes=0;
 for(auto c:s){if(c==L'\\'){++slashes;continue;}if(c==L'\"'){r.append(slashes*2+1,L'\\');r+=c;}else{r.append(slashes,L'\\');r+=c;}slashes=0;}
 r.append(slashes*2,L'\\');return r+L"\"";
}
static bool systemUser(){
 Handle token;check(OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&token.h));DWORD n=0;GetTokenInformation(token,TokenUser,nullptr,0,&n);
 std::vector<BYTE>b(n);check(GetTokenInformation(token,TokenUser,b.data(),n,&n));return IsWellKnownSid(reinterpret_cast<TOKEN_USER*>(b.data())->User.Sid,WinLocalSystemSid)!=FALSE;
}
static std::wstring root(){wchar_t p[32768];DWORD n=GetEnvironmentVariableW(L"ProgramData",p,32768);require(n&&n<32768);return std::wstring(p)+L"\\SiyuePrinter";}
static Job parse(int argc,wchar_t** argv){
 require(argc==8&&std::wstring(argv[1])==L"-new-window"&&std::wstring(argv[2])==L"-silent"&&std::wstring(argv[3])==L"-print-to"&&std::wstring(argv[5])==L"-print-settings");
 Job j;j.queue=argv[4];j.file=fs::absolute(argv[7]).wstring();j.name=fs::path(j.file).stem().wstring();
 std::wstring s=argv[6],prefix=L"paper=A4,shrink,";require(s.rfind(prefix,0)==0);s=s.substr(prefix.size());
 if(s.rfind(L"duplexlong,",0)==0){j.duplex=true;s=s.substr(11);}else{require(s.rfind(L"simplex,",0)==0);s=s.substr(8);}
 require(s.size()>=2&&s.back()==L'x');s.pop_back();require(s.size()<=2&&std::all_of(s.begin(),s.end(),[](wchar_t c){return c>=L'0'&&c<=L'9';}));j.copies=std::stoi(s);require(j.copies>=1&&j.copies<=99);
 require(!j.queue.empty()&&j.queue.size()<256&&fs::path(j.file).extension()==L".pdf");return j;
}
static fs::path pdfiumComponent(){
 wchar_t self[32768];require(GetModuleFileNameW(nullptr,self,32768)>0);
 auto path=fs::path(self).parent_path()/L"pdfium.dll";
 HCRYPTPROV provider=0;check(CryptAcquireContextW(&provider,nullptr,nullptr,PROV_RSA_AES,CRYPT_VERIFYCONTEXT));
 struct CryptoGuard{HCRYPTPROV p;HCRYPTHASH h=0;~CryptoGuard(){if(h)CryptDestroyHash(h);CryptReleaseContext(p,0);}} hash{provider};
 check(CryptCreateHash(provider,CALG_SHA_256,0,0,&hash.h));std::ifstream file(path,std::ios::binary);require(file.good(),ERROR_FILE_NOT_FOUND);char data[65536];
 while(file){file.read(data,sizeof(data));auto n=file.gcount();if(n)check(CryptHashData(hash.h,reinterpret_cast<const BYTE*>(data),static_cast<DWORD>(n),0));}
 require(file.eof(),ERROR_READ_FAULT);BYTE digest[32];DWORD n=sizeof(digest);check(CryptGetHashParam(hash.h,HP_HASHVAL,digest,&n,0));
 std::string hex;const char* digits="0123456789abcdef";for(auto b:digest){hex+=digits[b>>4];hex+=digits[b&15];}
 require(hex=="a318c39c137482dc4687c2973b7ec1c27fc1c20253cf2dc31928f922eab89e8f",ERROR_INVALID_DATA);return path;
}
struct Pdfium {
 HMODULE module=nullptr;FPDF_DOCUMENT document=nullptr;std::vector<char> source;
#define PDF_FN(name) decltype(&name) name##Fn=nullptr;
 PDF_FN(FPDF_InitLibrary) PDF_FN(FPDF_DestroyLibrary) PDF_FN(FPDF_LoadMemDocument64) PDF_FN(FPDF_CloseDocument)
 PDF_FN(FPDF_GetPageCount) PDF_FN(FPDF_LoadPage) PDF_FN(FPDF_ClosePage) PDF_FN(FPDF_GetPageWidthF) PDF_FN(FPDF_GetPageHeightF)
 PDF_FN(FPDFBitmap_CreateEx) PDF_FN(FPDFBitmap_Destroy) PDF_FN(FPDFBitmap_FillRect) PDF_FN(FPDF_RenderPageBitmap)
#undef PDF_FN
 Pdfium(){
  phase=L"verify-pdf-renderer";auto path=pdfiumComponent();module=LoadLibraryExW(path.c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_SYSTEM32);require(module!=nullptr,GetLastError());
#define LOAD_PDF(name) name##Fn=reinterpret_cast<decltype(name##Fn)>(GetProcAddress(module,#name));require(name##Fn!=nullptr,ERROR_PROC_NOT_FOUND);
  LOAD_PDF(FPDF_InitLibrary) LOAD_PDF(FPDF_DestroyLibrary) LOAD_PDF(FPDF_LoadMemDocument64) LOAD_PDF(FPDF_CloseDocument)
  LOAD_PDF(FPDF_GetPageCount) LOAD_PDF(FPDF_LoadPage) LOAD_PDF(FPDF_ClosePage) LOAD_PDF(FPDF_GetPageWidthF) LOAD_PDF(FPDF_GetPageHeightF)
  LOAD_PDF(FPDFBitmap_CreateEx) LOAD_PDF(FPDFBitmap_Destroy) LOAD_PDF(FPDFBitmap_FillRect) LOAD_PDF(FPDF_RenderPageBitmap)
#undef LOAD_PDF
  FPDF_InitLibraryFn();
 }
 ~Pdfium(){if(document)FPDF_CloseDocumentFn(document);if(module){FPDF_DestroyLibraryFn();FreeLibrary(module);}}
 void open(const std::wstring& path){phase=L"load-pdfium-document";std::ifstream input(fs::path(path),std::ios::binary|std::ios::ate);require(input.good(),ERROR_FILE_NOT_FOUND);auto size=input.tellg();require(size>0&&size<256*1024*1024);source.resize(static_cast<size_t>(size));input.seekg(0);require(static_cast<bool>(input.read(source.data(),size)),ERROR_READ_FAULT);document=FPDF_LoadMemDocument64Fn(source.data(),source.size(),nullptr);require(document!=nullptr);}
 uint32_t count(){int count=FPDF_GetPageCountFn(document);require(count>0&&count<=2000);return static_cast<uint32_t>(count);}
 std::vector<uint8_t> render(uint32_t index,int& width,int& height){
  auto page=FPDF_LoadPageFn(document,static_cast<int>(index));require(page!=nullptr);
  struct PageGuard{Pdfium* owner;FPDF_PAGE page;~PageGuard(){owner->FPDF_ClosePageFn(page);}} pg{this,page};
  double w=FPDF_GetPageWidthFFn(page),h=FPDF_GetPageHeightFFn(page);require(w>0&&h>0);double scale=std::min(300.0/72.0,3508.0/std::max(w,h));width=std::max(1,static_cast<int>(w*scale));height=std::max(1,static_cast<int>(h*scale));
  std::vector<uint8_t> pixels(static_cast<size_t>(width)*height*4,255);auto bitmap=FPDFBitmap_CreateExFn(width,height,FPDFBitmap_BGRA,pixels.data(),width*4);require(bitmap!=nullptr);
  struct BitmapGuard{Pdfium* owner;FPDF_BITMAP bitmap;~BitmapGuard(){owner->FPDFBitmap_DestroyFn(bitmap);}} bg{this,bitmap};
  FPDFBitmap_FillRectFn(bitmap,0,0,width,height,0xffffffff);FPDF_RenderPageBitmapFn(bitmap,page,0,0,width,height,0,FPDF_ANNOT|FPDF_PRINTING);return pixels;
 }
};
static void nativePrint(const Job& j){
 phase=L"initialize-pdf";init_apartment(apartment_type::multi_threaded);PdfDocument pdf{nullptr};std::unique_ptr<Pdfium> alternate;
 if(j.compat){alternate=std::make_unique<Pdfium>();alternate->open(j.file);}
 else{try{phase=L"open-pdf-file";auto file=StorageFile::GetFileFromPathAsync(j.file).get();phase=L"load-pdf-document";pdf=PdfDocument::LoadFromFileAsync(file).get();}
 catch(const hresult_error& e){if(e.code()!=REGDB_E_CLASSNOTREG)throw;alternate=std::make_unique<Pdfium>();alternate->open(j.file);}}
 uint32_t pageCount=alternate?alternate->count():pdf.PageCount();require(pageCount>0&&pageCount<=2000);
 phase=L"user-driver-settings";HANDLE printer=nullptr;check(OpenPrinterW(const_cast<LPWSTR>(j.queue.c_str()),&printer,nullptr));
 struct ClosePrinterGuard{HANDLE h;~ClosePrinterGuard(){ClosePrinter(h);}} pg{printer};
 LONG needed=DocumentPropertiesW(nullptr,printer,const_cast<LPWSTR>(j.queue.c_str()),nullptr,nullptr,0);require(needed>0);
 std::vector<BYTE>dev(static_cast<size_t>(needed));auto dm=reinterpret_cast<DEVMODEW*>(dev.data());
 require(DocumentPropertiesW(nullptr,printer,const_cast<LPWSTR>(j.queue.c_str()),dm,nullptr,DM_OUT_BUFFER)==IDOK);
 // Level 9 is the per-user DEVMODE. Preserve the driver's private authentication data.
 DWORD bytes=0;GetPrinterW(printer,9,nullptr,0,&bytes);std::vector<BYTE>user(bytes);
 if(bytes&&GetPrinterW(printer,9,user.data(),bytes,&bytes)){
  auto u=reinterpret_cast<PRINTER_INFO_9W*>(user.data())->pDevMode;
  if(u&&static_cast<size_t>(u->dmSize)+u->dmDriverExtra<=dev.size())memcpy(dm,u,static_cast<size_t>(u->dmSize)+u->dmDriverExtra);
 }
 dm->dmFields|=DM_PAPERSIZE|DM_COPIES|DM_COLLATE|DM_DUPLEX;dm->dmPaperSize=DMPAPER_A4;dm->dmCopies=static_cast<short>(j.copies);dm->dmCollate=DMCOLLATE_TRUE;dm->dmDuplex=j.duplex?DMDUP_VERTICAL:DMDUP_SIMPLEX;
 require(DocumentPropertiesW(nullptr,printer,const_cast<LPWSTR>(j.queue.c_str()),dm,dm,DM_IN_BUFFER|DM_OUT_BUFFER)==IDOK);
 require(dm->dmCopies==j.copies&&dm->dmDuplex==(j.duplex?DMDUP_VERTICAL:DMDUP_SIMPLEX),ERROR_NOT_SUPPORTED);
 phase=L"create-printer-context";HDC dc=CreateDCW(L"WINSPOOL",j.queue.c_str(),nullptr,dm);require(dc!=nullptr,GetLastError());
 struct DcGuard{HDC h;bool active=false;~DcGuard(){if(active)AbortDoc(h);DeleteDC(h);}} guard{dc};
 DOCINFOW info{};info.cbSize=sizeof(info);info.lpszDocName=j.name.c_str();
 if(j.checkOnly){phase=L"preflight-passed";return;}
 phase=L"start-document";require(StartDocW(dc,&info)>0,GetLastError());guard.active=true;
 const int width=GetDeviceCaps(dc,HORZRES),height=GetDeviceCaps(dc,VERTRES);require(width>0&&height>0);
 for(uint32_t p=0;p<pageCount;++p){
  phase=L"render-page";int pw=0,ph=0;std::vector<uint8_t> pixels;
  if(alternate){pixels=alternate->render(p,pw,ph);}
  else{auto page=pdf.GetPage(p);auto size=page.Size();double scale=std::min(300.0/96.0,3508.0/std::max(size.Width,size.Height));
   PdfPageRenderOptions options;options.DestinationWidth(static_cast<uint32_t>(std::max(1.0,size.Width*scale)));options.DestinationHeight(static_cast<uint32_t>(std::max(1.0,size.Height*scale)));
   InMemoryRandomAccessStream stream;page.RenderToStreamAsync(stream,options).get();stream.Seek(0);
   auto decoder=BitmapDecoder::CreateAsync(stream).get();auto data=decoder.GetPixelDataAsync(BitmapPixelFormat::Bgra8,BitmapAlphaMode::Ignore,BitmapTransform{},ExifOrientationMode::IgnoreExifOrientation,ColorManagementMode::DoNotColorManage).get().DetachPixelData();
   pw=static_cast<int>(decoder.PixelWidth());ph=static_cast<int>(decoder.PixelHeight());pixels.assign(data.begin(),data.end());page.Close();}
  require(pw>0&&ph>0&&pixels.size()==static_cast<size_t>(pw)*ph*4);
  BITMAPINFO bitmap{};bitmap.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);bitmap.bmiHeader.biWidth=pw;bitmap.bmiHeader.biHeight=-ph;bitmap.bmiHeader.biPlanes=1;bitmap.bmiHeader.biBitCount=32;bitmap.bmiHeader.biCompression=BI_RGB;
  phase=L"print-page";require(StartPage(dc)>0,GetLastError());double fit=std::min(static_cast<double>(width)/pw,static_cast<double>(height)/ph);int w=static_cast<int>(pw*fit),h=static_cast<int>(ph*fit);
  SetStretchBltMode(dc,HALFTONE);SetBrushOrgEx(dc,0,0,nullptr);
  int result=StretchDIBits(dc,(width-w)/2,(height-h)/2,w,h,0,0,pw,ph,pixels.data(),&bitmap,DIB_RGB_COLORS,SRCCOPY);require(result!=GDI_ERROR&&result!=0,GetLastError());require(EndPage(dc)>0,GetLastError());
 }
 phase=L"end-document";require(EndDoc(dc)>0,GetLastError());guard.active=false;
}
static DWORD asInteractive(Job j){
 phase=L"select-user-session";PWTS_SESSION_INFOW sessions=nullptr;DWORD count=0;check(WTSEnumerateSessionsW(WTS_CURRENT_SERVER_HANDLE,0,1,&sessions,&count));
 std::vector<DWORD>active;for(DWORD i=0;i<count;i++)if(sessions[i].SessionId&&sessions[i].State==WTSActive)active.push_back(sessions[i].SessionId);WTSFreeMemory(sessions);require(active.size()==1,ERROR_NO_SUCH_LOGON_SESSION);
 Handle token;check(WTSQueryUserToken(active[0],&token.h));DWORD n=0;GetTokenInformation(token,TokenUser,nullptr,0,&n);std::vector<BYTE>user(n);check(GetTokenInformation(token,TokenUser,user.data(),n,&n));
 LPWSTR sid=nullptr;check(ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(user.data())->User.Sid,&sid));std::wstring sidText=sid;LocalFree(sid);
 // Only a server-owned job UUID under the protected agent data tree may cross sessions.
 auto source=fs::weakly_canonical(j.file);auto base=fs::weakly_canonical(root());auto relative=source.lexically_relative(base);require(!relative.empty()&&*relative.begin()!=L".."&&relative!=source);
 GUID jobGuid{};require(SUCCEEDED(CLSIDFromString((L"{"+j.name+L"}").c_str(),&jobGuid)));
 phase=L"prepare-user-file";auto bridge=base/L"native-print";fs::create_directories(bridge);GUID unique;check_hresult(CoCreateGuid(&unique));wchar_t id[40];StringFromGUID2(unique,id,40);auto dir=bridge/id;
 auto sddl=L"D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;GRGX;;;"+sidText+L")";PSECURITY_DESCRIPTOR sd=nullptr;check(ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(),SDDL_REVISION_1,&sd,nullptr));SECURITY_ATTRIBUTES sa{sizeof(sa),sd,FALSE};BOOL created=CreateDirectoryW(dir.c_str(),&sa);DWORD error=GetLastError();LocalFree(sd);require(created,error);
 struct FilesGuard{fs::path p;~FilesGuard(){std::error_code ec;fs::remove_all(p,ec);}} files{dir};
 auto copy=dir/(j.name+L".pdf");check(CopyFileW(source.c_str(),copy.c_str(),TRUE));
 // CopyFile can preserve the source DACL. Explicitly grant this one session read-only access.
 sd=nullptr;check(ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(),SDDL_REVISION_1,&sd,nullptr));
 BOOL secured=SetFileSecurityW(copy.c_str(),DACL_SECURITY_INFORMATION|PROTECTED_DACL_SECURITY_INFORMATION,sd);error=GetLastError();LocalFree(sd);require(secured,error);
 wchar_t self[32768];require(GetModuleFileNameW(nullptr,self,32768)>0);std::wstring command=quote(self)+L" --native-user "+quote(j.queue)+L" "+std::to_wstring(j.copies)+L" "+(j.duplex?L"1":L"0")+L" "+quote(copy.wstring())+L" "+quote(j.name)+(j.checkOnly?L" --check":L"")+(j.compat?L" --pdfium":L"");
 phase=L"start-user-print";LPVOID env=nullptr;check(CreateEnvironmentBlock(&env,token,FALSE));
 STARTUPINFOW start{};start.cb=sizeof(start);wchar_t desktop[]=L"winsta0\\default";start.lpDesktop=desktop;PROCESS_INFORMATION pi{};
 BOOL started=CreateProcessAsUserW(token,self,command.data(),nullptr,nullptr,FALSE,CREATE_UNICODE_ENVIRONMENT|CREATE_NO_WINDOW|CREATE_SUSPENDED,env,fs::path(self).parent_path().c_str(),&start,&pi);error=GetLastError();DestroyEnvironmentBlock(env);require(started,error);
 Handle process{pi.hProcess},thread{pi.hThread},lifetime{CreateJobObjectW(nullptr,nullptr)};JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};limits.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
 if(!lifetime.h||!SetInformationJobObject(lifetime,JobObjectExtendedLimitInformation,&limits,sizeof(limits))||!AssignProcessToJobObject(lifetime,process)){error=GetLastError();TerminateProcess(process,error);WaitForSingleObject(process,5000);throw hresult_error(HRESULT_FROM_WIN32(error));}
 require(ResumeThread(thread)!=static_cast<DWORD>(-1),GetLastError());phase=L"wait-user-print";
 if(WaitForSingleObject(process,165000)!=WAIT_OBJECT_0){TerminateJobObject(lifetime,ERROR_TIMEOUT);WaitForSingleObject(process,5000);return ERROR_TIMEOUT;}
 DWORD code=0;check(GetExitCodeProcess(process,&code));
 // Printer drivers can continue their work in child processes after EndDoc returns.
 // Keep failure/parent-death cleanup, but never kill those workers after success.
 if(code==0){phase=L"release-driver-processes";limits.BasicLimitInformation.LimitFlags=0;check(SetInformationJobObject(lifetime,JobObjectExtendedLimitInformation,&limits,sizeof(limits)));}
 return code;
}
static void record(DWORD code){
 try{wchar_t path[32768];const wchar_t* env=systemUser()?L"ProgramData":L"LOCALAPPDATA";DWORD n=GetEnvironmentVariableW(env,path,32768);if(!n||n>=32768)return;
 auto dir=fs::path(path)/L"SiyueNativePrint";fs::create_directories(dir);std::ofstream out(dir/L"last-result.txt",std::ios::trunc);
 std::wstring stage=phase;out<<"version=0.1.4\nphase="<<std::string(stage.begin(),stage.end())<<"\ncode=0x"<<std::hex<<code<<"\n";
 }catch(...){}
}
int wmain(int argc,wchar_t** argv){
 try {
  if(argc==2&&std::wstring(argv[1])==L"--version"){std::wcout<<L"Siyue Windows Native Print 0.1.4\n";return 0;}
  if(argc>=7&&argc<=9&&std::wstring(argv[1])==L"--native-user"){
   require(!systemUser(),ERROR_ACCESS_DENIED);Job j;j.queue=argv[2];j.copies=std::stoi(argv[3]);require(j.copies>=1&&j.copies<=99);require(std::wstring(argv[4])==L"0"||std::wstring(argv[4])==L"1");j.duplex=std::wstring(argv[4])==L"1";j.file=argv[5];j.name=argv[6];for(int i=7;i<argc;++i){auto flag=std::wstring(argv[i]);require(flag==L"--check"||flag==L"--pdfium");if(flag==L"--check")j.checkOnly=true;else j.compat=true;}nativePrint(j);record(0);return 0;
  }
  require(argc>=8&&argc<=10);auto j=parse(8,argv);for(int i=8;i<argc;++i){auto flag=std::wstring(argv[i]);require(flag==L"--check"||flag==L"--pdfium");if(flag==L"--check")j.checkOnly=true;else j.compat=true;}if(systemUser()){auto code=asInteractive(j);record(code);return static_cast<int>(code);}nativePrint(j);record(0);return 0;
 }catch(const hresult_error& e){record(static_cast<DWORD>(e.code()));std::wcerr<<L"native-print phase="<<phase<<L" hresult="<<std::hex<<static_cast<unsigned long>(e.code())<<L"\n";return 70;}catch(...){record(ERROR_GEN_FAILURE);std::wcerr<<L"native-print phase="<<phase<<L" failed\n";return 71;}
}
