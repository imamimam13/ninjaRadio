import sys

# Alias dmr_utils3 to this package if not already installed globally
if "dmr_utils3" not in sys.modules:
    import core.dmr_bptc as dmr_bptc
    sys.modules["dmr_utils3"] = dmr_bptc