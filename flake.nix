{
  description = "minecraft-mod";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
  };

  outputs = inputs @ {
    self,
    nixpkgs,
    ...
  }: let
    inherit (self) outputs;

    systems = [
      "aarch64-linux"
      "i686-linux"
      "x86_64-linux"
      "aarch64-darwin"
      "x86_64-darwin"
    ];

    forAllSystems = nixpkgs.lib.genAttrs systems;

    pkgsFor = system:
      import nixpkgs {
        inherit system;
      };
  in {
    devShells = forAllSystems (system: let
      pkgs = pkgsFor system;
    in {
      main = pkgs.mkShell {
        name = "minecraft-mod-dev-shell";
        buildInputs = with pkgs; [gradle jdk21];
      };
    });
  };
}
