# Portable writer fixtures

`native-meal-v8.zip` is the synthetic archive produced by native RecipeCompositionTests from commit 8289a37bee58d99c20f35768f3a5a5ae5b03bef1, successful iOS run 38305336046, artifact 11773227013. It contains three recipes, their exact current sources, customized attempt setup and a meal combination. It is kept byte-for-byte to verify the Android reader.

CrossPlatformArchiveTest reads this input and uses the real Android repository and ZIP writer to add a measured stovetop note, precise ingredients, Celsius reading, separately owned source/reference photos, a frozen historical rule and a duration rule. The generated `android-meal-v9.zip` is collected from emulator storage for the native reader. All data is synthetic.
