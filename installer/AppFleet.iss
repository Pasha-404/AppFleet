#ifndef AppVersion
  #error AppVersion must be supplied by Gradle
#endif
#ifndef AppId
  #error AppId must be supplied by Gradle
#endif
#ifndef RepositoryUrl
  #error RepositoryUrl must be supplied by Gradle
#endif
#ifndef AppImageDir
  #error AppImageDir must be supplied by Gradle
#endif
#ifndef OutputDir
  #error OutputDir must be supplied by Gradle
#endif

#define AppName "AppFleet"
#define TechnicalName "AppFleet"
#define MainExecutable "AppFleet.exe"

[Setup]
AppId={#AppId}
AppName={#AppName}
AppVersion={#AppVersion}
UninstallDisplayName={#AppName}
AppPublisher=PashaApps
DefaultDirName={localappdata}\Programs\PashaApps\{#TechnicalName}
DefaultGroupName={#AppName}
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UsePreviousAppDir=yes
CloseApplications=yes
RestartApplications=no
Compression=lzma2
SolidCompression=yes
OutputDir={#OutputDir}
OutputBaseFilename={#TechnicalName}-Setup-{#AppVersion}-x64
DisableProgramGroupPage=yes
WizardStyle=modern
SetupIconFile=..\assets\AppFleet.ico
UninstallDisplayIcon={app}\{#MainExecutable}

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; GroupDescription: "Ярлыки:"; Flags: unchecked

[InstallDelete]
; Only known replaceable app-image content may be deleted during an update.
Type: files; Name: "{app}\{#MainExecutable}"
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\icons"

[Files]
; The app-image is already fully prepared inside the signed installer before any old file is replaced.
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#MainExecutable}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#MainExecutable}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "SchemaVersion"; ValueData: "1"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "AppId"; ValueData: "{#AppId}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Name"; ValueData: "{#AppName}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "TechnicalName"; ValueData: "{#TechnicalName}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Version"; ValueData: "{#AppVersion}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "InstallLocation"; ValueData: "{app}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Executable"; ValueData: "{app}\{#MainExecutable}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "ProcessName"; ValueData: "{#MainExecutable}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "RepositoryUrl"; ValueData: "{#RepositoryUrl}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "InstallerType"; ValueData: "inno"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "InstalledBy"; ValueData: "installer"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Publisher"; ValueData: "PashaApps"

[Run]
Filename: "{app}\{#MainExecutable}"; Description: "Запустить {#AppName}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Do not touch %APPDATA% or cache/logs. Remove only known program files and an empty program directory.
Type: files; Name: "{app}\{#MainExecutable}"
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\icons"
Type: dirifempty; Name: "{app}"
