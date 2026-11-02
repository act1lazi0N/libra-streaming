#requires -Version 7.0
# Dot-sourced by the smoke runners. `docker compose down` exiting 0 does not prove removal, so ask Docker for anything
# still labeled with the disposable project: containers, volumes, networks and the images Compose built for it.
function Assert-ComposeProjectRemoved([Parameter(Mandatory)][string]$Project) {
    $filter = "label=com.docker.compose.project=$Project"
    $left = @()
    foreach ($kind in @(@('container', 'ls', '--all'), @('volume', 'ls'), @('network', 'ls'), @('image', 'ls'))) {
        $ids = @(& docker @kind --quiet --filter $filter | Where-Object { $_ })
        if ($LASTEXITCODE -ne 0) { throw "Unable to list Docker $($kind[0])s of project $Project." }
        if ($ids.Count) { $left += "$($ids.Count) $($kind[0])(s)" }
    }
    if ($left) { throw "Compose project $Project was not fully removed: $($left -join ', ') remain." }
}
