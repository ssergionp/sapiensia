{{- range . }}{{- $target := .Target }}{{- range .Vulnerabilities }}{{ .Severity }} {{ .VulnerabilityID }} {{ .PkgName }} {{ .InstalledVersion }} -> {{ .FixedVersion }} ({{ $target }})
{{ end }}{{- end }}
