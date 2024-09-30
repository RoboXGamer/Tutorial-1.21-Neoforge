package net.roboxgamer.tutorialmod.block.entity.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import net.neoforged.neoforge.network.PacketDistributor;
import net.roboxgamer.tutorialmod.TutorialMod;
import net.roboxgamer.tutorialmod.block.entity.ModBlockEntities;
import net.roboxgamer.tutorialmod.menu.MechanicalCrafterMenu;
import net.roboxgamer.tutorialmod.network.ItemStackPayload;
import net.roboxgamer.tutorialmod.util.Constants;
import net.roboxgamer.tutorialmod.util.CustomRecipeExtender;
import net.roboxgamer.tutorialmod.util.RedstoneManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

import static net.roboxgamer.tutorialmod.util.Constants.MECHANICAL_CRAFTER_BLACKLISTED_RECIPES;
import static net.roboxgamer.tutorialmod.util.Constants.MECHANICAL_CRAFTER_SPECIAL_RECIPES;
import static net.roboxgamer.tutorialmod.util.RedstoneManager.REDSTONE_MODE_MAP;

public class MechanicalCrafterBlockEntity extends BlockEntity implements MenuProvider {
  public Component TITLE = Component.translatable("block.tutorialmod.mechanical_crafter_block");
  
  public static final int INPUT_SLOTS_COUNT = 9;
  public static final int OUTPUT_SLOTS_COUNT = 9;
  public static final int CRAFT_RESULT_SLOT = 0;
  public static final int[] CRAFT_RECIPE_SLOTS = new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 };
  private static final int RESULT_SLOT = 0;
  
  private int tc = 0;
  private CraftingRecipe recipe;
  private ItemStack result;
  private int remainItemToggleValue = 1;
  private List<ItemStack> craftingInputList;
  
  private final RedstoneManager redstoneManager = new RedstoneManager(this);

  public class CustomItemStackHandler extends ItemStackHandler {
    public CustomItemStackHandler(int size) {
      super(size);
    }

    @Override
    protected void onContentsChanged(int slot) {
      MechanicalCrafterBlockEntity.this.setChanged();
    }

    public NonNullList<ItemStack> getStacks() {
      return this.stacks;
    }

    public NonNullList<ItemStack> getStacksCopy(int startIndex) {
      var t = NonNullList.withSize(this.stacks.size(), ItemStack.EMPTY);
      for (int i = startIndex; i < this.stacks.size(); i++) {
        t.set(i - startIndex, this.stacks.get(i).copy());
      }
      return t;
    }

    public NonNullList<ItemStack> getStacksCopy() {
      return this.getStacksCopy(0);
    }

    public boolean isCompletelyEmpty() {
      // if all the slots are empty, return true
      boolean isEmpty = true;
      for (ItemStack stack : this.stacks) {
        if (!stack.isEmpty()) {
          isEmpty = false;
          break;
        }
      }
      return isEmpty;
    }

    public boolean isFull() {
      boolean isFull = true;
      for (ItemStack stack : this.stacks) {
        if (stack.getCount() < stack.getMaxStackSize()) {
          isFull = false;
          break;
        }
      }
      return isFull;
    }
  }

  public class CraftingSlotHandler extends CustomItemStackHandler {
    public CraftingSlotHandler(int size) {
      super(size);
    }

    @Override
    protected void onContentsChanged(int slot) {
      super.onContentsChanged(slot);
      if (slot == RESULT_SLOT)
        return;
      Level level = MechanicalCrafterBlockEntity.this.getLevel();
      if (level == null || level.isClientSide() || !(level instanceof ServerLevel slevel))
        return;
      BlockEntity blockEntity = slevel.getBlockEntity(MechanicalCrafterBlockEntity.this.getBlockPos());
      if (!(blockEntity instanceof MechanicalCrafterBlockEntity be))
        return;
      be.recipe = be.getRecipe(slevel);
      if (be.recipe == null) {
        be.result = null;
      }
      if (be.result != null) {
        PacketDistributor.sendToAllPlayers(new ItemStackPayload(be.result, be.getBlockPos()));
        be.craftingSlots.setStackInSlot(MechanicalCrafterBlockEntity.RESULT_SLOT, be.result);
      }
    }
    
    NonNullList<Ingredient> getIngredientsList() {
      return NonNullList.copyOf(
          this.getStacksCopy(1).stream()
              .map(itemStack -> itemStack.copyWithCount(1))
              .map(Ingredient::of)
              .toList());
    }
  }

  CustomItemStackHandler inputSlots = new CustomItemStackHandler(9);
  CustomItemStackHandler outputSlots = new CustomItemStackHandler(9);
  CraftingSlotHandler craftingSlots = new CraftingSlotHandler(10);

  // Combine handler of input and output slots
  CombinedInvWrapper combinedInvHandler = new CombinedInvWrapper(inputSlots, outputSlots) {

    @Override
    public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
      // Get the number of input slots
      int inputSlotCount = inputSlots.getSlots();

      // Ensure the slot is within the total valid slot range
      if (slot < 0 || slot >= getSlots()) {
        return ItemStack.EMPTY; // Slot out of bounds, return empty
      }

      // Check if the slot is within the outputSlots range
      if (slot >= inputSlotCount) {
        // Calculate the corresponding slot in the outputSlots handler
        int outputSlot = slot - inputSlotCount;

        // Extract the item from the outputSlots handler
        return outputSlots.extractItem(outputSlot, amount, simulate);
      } else {
        // If the slot is in the inputSlots, prevent extraction and return an empty
        // ItemStack
        return ItemStack.EMPTY;
      }
    }

    @Override
    public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
      // Get the number of input slots
      int inputSlotCount = inputSlots.getSlots();

      // Ensure the slot is within the total valid slot range
      if (slot < 0 || slot >= getSlots()) {
        return stack; // Slot out of bounds, return stack unchanged
      }

      // Check if the slot is within the inputSlots range
      if (slot < inputSlotCount) {
        // Insert the item into the inputSlots handler
        return inputSlots.insertItem(slot, stack, simulate);
      } else {
        // If the slot is in the outputSlots, prevent insertion and return the stack
        // unchanged
        return stack;
      }
    }

    @Override
    public int getSlots() {
      // The total number of slots is the sum of input and output slots
      return inputSlots.getSlots() + outputSlots.getSlots();
    }

    @Override
    public int getSlotLimit(int slot) {
      // Get the number of input slots
      int inputSlotCount = inputSlots.getSlots();

      // Ensure the slot is within the total valid slot range
      if (slot < 0 || slot >= getSlots()) {
        return 0; // Invalid slot, return limit 0
      }

      // Check if the slot is within the input or output slots and return their
      // respective limits
      if (slot < inputSlotCount) {
        return inputSlots.getSlotLimit(slot); // Input slot
      } else {
        int outputSlot = slot - inputSlotCount;
        return outputSlots.getSlotLimit(outputSlot); // Output slot
      }
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
      // Get the number of input slots
      int inputSlotCount = inputSlots.getSlots();

      // Ensure the slot is within the total valid slot range
      if (slot < 0 || slot >= getSlots()) {
        return false; // Invalid slot, no item is valid
      }

      // Check if the slot is within the input slots and allow only valid items for
      // input slots
      if (slot < inputSlotCount) {
        return inputSlots.isItemValid(slot, stack); // Input slot
      } else {
        return false; // No item is valid for output slots
      }
    }
  };

  public MechanicalCrafterBlockEntity(BlockPos pos, BlockState blockState) {
    super(ModBlockEntities.MECHANICAL_CRAFTER_BE.get(), pos, blockState);
  }

  @Override
  public @NotNull Component getDisplayName() {
    return TITLE;
  }

  public void tick() {
    // Ticking logic
    this.tc++;
    if (everySecond(60))
      this.tc = 0; // Every 1 minute
    // TutorialMod.LOGGER.debug("tc: {}", this.tc);
    if (this.level == null || this.level.isClientSide() || !(this.level instanceof ServerLevel slevel))
      return;

    if (this.tc == 20) {
      this.recipe = getRecipe(slevel);
      if (this.recipe == null) {
        this.result = null;
      }
      if (this.result != null) {
        PacketDistributor.sendToAllPlayers(new ItemStackPayload(this.result, this.getBlockPos()));
        this.craftingSlots.setStackInSlot(RESULT_SLOT, this.result);
      }
    }
    
    // Redstone control logic
    boolean powered = level.hasNeighborSignal(this.getBlockPos());
    
    switch (this.redstoneManager.getRedstoneMode()) {
      case ALWAYS_ON:
        break; // No additional check, always allows crafting
      
      case REDSTONE_ON:
        if (!powered) return; // Only craft if receiving redstone power
        break;
      
      case REDSTONE_OFF:
        if (powered) return; // Stop crafting if receiving redstone power
        break;
    }
    // *** Logic for crafting ***

    if (everySecond(0.5)) { // Every 10 ticks
      if (canCraft()) {
        // TutorialMod.LOGGER.info("Can Craft!");
        craft();
      } else {
        autoImport();
        if (canCraft()){
          craft();
        }
      }
    }
    autoExport();
  }
  
  private void autoImport() {
    if (this.recipe == null) return;
    
    List<IngredientNeed> neededItems = calculateNeededItems();
    if (neededItems.isEmpty()) return;
    
    importFromAdjacentInventories(neededItems);
  }
  
  private record IngredientNeed(Ingredient ingredient, int slot, int count) {
  }
  
  private List<IngredientNeed> calculateNeededItems() {
    List<IngredientNeed> neededItems = new ArrayList<>();
    List<Ingredient> recipeIngredients = this.recipe.getIngredients();
    NonNullList<ItemStack> inputStacks = this.inputSlots.getStacksCopy();
    for (int i = 0; i < recipeIngredients.size(); i++) {
      Ingredient ingredient = recipeIngredients.get(i);
      if (ingredient == Ingredient.EMPTY) continue; // Skip empty ingredients
      
      ItemStack[] matchingStacks = ingredient.getItems();
      if (matchingStacks.length == 0) continue; // Skip if no matching items
      ItemStack requiredStack = matchingStacks[0].copy(); // Use the first matching item as a representative
      int requiredCount = requiredStack.getCount();
      int foundCount = 0;
      
      // Check all input slots for this ingredient
      for (ItemStack slotStack : inputStacks) {
        if (!slotStack.isEmpty() && ingredient.test(slotStack) && slotStack.getCount() >= requiredCount) {
          foundCount += requiredCount;
          slotStack.shrink(requiredCount);
          break;
        }
      }
      
      if (foundCount < requiredCount) {
        neededItems.add(new IngredientNeed(ingredient, i, requiredCount - foundCount));
      }
    }
    
    return neededItems;
  }
  
  private void importFromAdjacentInventories(List<IngredientNeed> neededItems) {
    for (Direction direction : Direction.values()) {
      if (neededItems.isEmpty()) return;
      
      BlockPos pos = this.getBlockPos().relative(direction);
      if (this.level == null || this.level.isClientSide() || !(this.level instanceof ServerLevel))
        return;
      BlockEntity blockEntity = this.level.getBlockEntity(pos);
      if (blockEntity == null) continue;
      
      IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, direction);
      if (cap == null) continue;
      
      Iterator<IngredientNeed> iterator = neededItems.iterator();
      while (iterator.hasNext()) {
        IngredientNeed need = iterator.next();
        boolean found = false;
        
        for (int slot = 0; slot < cap.getSlots(); slot++) {
          ItemStack extractedStack = cap.extractItem(slot, need.count, true); // Simulate extraction
          if (!extractedStack.isEmpty() && need.ingredient.test(extractedStack)) {
            ItemStack actualExtracted = cap.extractItem(slot, need.count, false); // Actually extract
            if (putInInputSlots(this.inputSlots, actualExtracted)) {
              found = true;
              break;
            }
          }
        }
        
        if (found) {
          iterator.remove(); // Remove this need from the list
        }
      }
    }
  }
  
  private boolean putInInputSlots(ItemStackHandler inputSlots, ItemStack stack) {
    // First, try to stack with existing items
    for (int i = 0; i < inputSlots.getSlots(); i++) {
      ItemStack existingStack = inputSlots.getStackInSlot(i);
      if (!existingStack.isEmpty() && ItemStack.isSameItemSameComponents(existingStack, stack)) {
        if (existingStack.getCount() < existingStack.getMaxStackSize()) {
          existingStack.grow(1);
          inputSlots.setStackInSlot(i, existingStack);
          return true;
        }
      }
    }
    
    // If stacking wasn't possible, find the first empty slot
    for (int i = 0; i < inputSlots.getSlots(); i++) {
      if (inputSlots.getStackInSlot(i).isEmpty()) {
        inputSlots.setStackInSlot(i, stack);
        return true;
      }
    }
    
    return false;
  }
  
  private void autoExport() {
  //  Get output slots
    if (this.level == null || this.level.isClientSide() || !(this.level instanceof ServerLevel))
      return;
    CustomItemStackHandler outputSlots = this.outputSlots;
    if (outputSlots.isCompletelyEmpty())
      return;
    for (Direction direction : Direction.values()) {
      BlockPos pos = this.getBlockPos().relative(direction);
      BlockState state = this.level.getBlockState(pos);
      if (state.hasBlockEntity() && state.getBlock() instanceof EntityBlock) {
        if (level.getBlockEntity(pos) instanceof MechanicalCrafterBlockEntity) return;
        // Get the item handler from the block entity
        IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, direction);
        if (cap == null)
          continue;
        
        // Iterate through the items to export to the block
        for (int i = 0; i < outputSlots.getSlots(); i++) {
          ItemStack itemToExport = outputSlots.getStackInSlot(i);
          if (itemToExport.isEmpty())
            continue;
          
          // Try to insert the item into the capability
          for (int j = 0; j < cap.getSlots(); j++) {
            ItemStack inserted = cap.insertItem(j, itemToExport, false);
            if (inserted.isEmpty()){
              outputSlots.setStackInSlot(i, ItemStack.EMPTY);
              break;
            }
            itemToExport.setCount(inserted.getCount()); // Update the count of the item remaining to export
          }
        }
      }
    }
  }

  private CraftingRecipe getRecipe(ServerLevel level) {
    if (this.craftingSlots.isCompletelyEmpty()) return null;
    RecipeManager recipes = level.getRecipeManager();
    var craftingSlotsStacksCopy = this.craftingSlots.getStacksCopy(1);
    CraftingInput input = CraftingInput.of(3, 3, craftingSlotsStacksCopy);
    this.craftingInputList = input.items();
    List<RecipeHolder<CraftingRecipe>> list = recipes.getRecipesFor(RecipeType.CRAFTING, input, level);
    if (list.isEmpty()) {
      this.result = null;
      this.craftingSlots.setStackInSlot(0, ItemStack.EMPTY);
      return null;
    }
    RecipeHolder<CraftingRecipe> foundRecipe = list.getFirst(); // TODO: Add support for multiple recipes conflicting with the same input
    // TutorialMod.LOGGER.debug("foundRecipe: {}", foundRecipe);
    
    ItemStack result = foundRecipe.value().assemble(input, level.registryAccess()).copy();
    if (result.isEmpty()) return null;
    this.result = result;
    // TutorialMod.LOGGER.debug("Result: {}", this.result);
    
    PacketDistributor.sendToAllPlayers(new ItemStackPayload(this.result, this.getBlockPos()));
    if (this.craftingSlots.getStackInSlot(RESULT_SLOT) != result)
      this.craftingSlots.setStackInSlot(RESULT_SLOT, this.result);
    
    
    
    CustomRecipeExtender<?> recipeToReturn = new CustomRecipeExtender<>(foundRecipe.value());
    
    // Blacklisted types of recipes
    if (isRecipe(foundRecipe.value(), Constants.RecipeTypes.BLACKLISTED)) {
      return null;
    }
    // Special types of recipes
    if (isRecipe(foundRecipe.value(), Constants.RecipeTypes.SPECIAL)){
      NonNullList<Ingredient> ingredients = this.craftingSlots.getIngredientsList();
      recipeToReturn.setIngredients(ingredients);
    }
    
    return recipeToReturn;
  }
  
  private boolean isRecipe(CraftingRecipe value, Constants.RecipeTypes type) {
    Class<?>[] recipes = switch (type) {
      case BLACKLISTED -> MECHANICAL_CRAFTER_BLACKLISTED_RECIPES;
      case SPECIAL -> MECHANICAL_CRAFTER_SPECIAL_RECIPES;
    };
    
    for (var entry : recipes) {
      if (entry.isInstance(value)) return true;
    }
    return false;
  }
  
  private CraftingInput getCraftingInputFromActualInput(List<ItemStack> items) {
    // Get a copy of the input slots' stacks
    var inputSlotStacks = this.inputSlots.getStacksCopy();

    // Make a copy of the items list passed as a parameter
    ArrayList<ItemStack> itemsToMatch = items.stream().map(ItemStack::copy)
        .collect(Collectors.toCollection(ArrayList::new));

    // Prepare a list for the crafting input with 9 slots (3x3)
    var matchedItems = NonNullList.withSize(9, ItemStack.EMPTY);

    // Iterate through the input slots and attempt to match the items
    for (int slotIndex = 0; slotIndex < inputSlotStacks.size(); slotIndex++) {
      ItemStack inputSlotItem = inputSlotStacks.get(slotIndex);

      // Iterate through the items to match
      for (int matchIndex = 0; matchIndex < itemsToMatch.size(); matchIndex++) {
        ItemStack matchingItem = itemsToMatch.get(matchIndex);

        // If items match by type, copy the count from the matching item
        if (ItemStack.isSameItem(matchingItem, inputSlotItem)) {
          matchedItems.set(slotIndex, inputSlotItem.copyWithCount(matchingItem.getCount()));

          // Remove the matched item from the list to avoid further matches
          itemsToMatch.remove(matchIndex);
          break; // Break the inner loop and move to the next input slot
        }
      }
    }

    // Construct the CraftingInput with the matched items
    return CraftingInput.of(3, 3, matchedItems);
  }

  private boolean inputCheck(List<ItemStack> input, List<Ingredient> ingredients) {
    // Iterate over each ingredient in the recipe
    for (Ingredient ingredient : ingredients) {
      boolean matched = false;
      // TutorialMod.LOGGER.info("Ingredient: {}", ingredient);
      if (ingredient.getItems().length == 0)
        continue;

      // Iterate over each item in the input slots
      for (ItemStack inputItem : input) {
        if (inputItem.isEmpty())
          continue;
        if (ingredient.test(inputItem)) {
          // Find the specific ingredient option that matches the input item
          for (ItemStack possibleMatch : ingredient.getItems()) {
            if (ItemStack.isSameItem(possibleMatch, inputItem)) {
              // Check if we have enough of the input item to satisfy the ingredient
              int requiredCount = possibleMatch.getCount();

              if (inputItem.getCount() >= requiredCount) {
                // If enough, decrease the input item count by the required amount
                inputItem.shrink(requiredCount);
                matched = true;
                break;
              } else {
                // If not enough, use all the input item and continue
                requiredCount -= inputItem.getCount();
                inputItem.setCount(0);
              }
            }
          }
        }

        // If we found a match and satisfied the ingredient, break out of the loop
        if (matched)
          break;
      }

      // If no input item could satisfy this ingredient, return false
      if (!matched) {
        // TutorialMod.LOGGER.debug("Crafting failed: Cannot craft");
        return false;
      }
    }
    // If all ingredients were matched with input items, return true
    return true;
  }

  private boolean canCraft() {
    // Check if we have necessary inputs and output space
    if (this.inputSlots.isCompletelyEmpty())
      return false;
    if (this.outputSlots.isFull())
      return false;
    // Check if the recipe and result are valid
    if (this.recipe == null || this.result == null || this.craftingInputList == null)
      return false;
    if (this.result.isEmpty())
      return false;
    // Get the list of ingredients from the recipe
    NonNullList<Ingredient> ingredients = this.recipe.getIngredients();

    // Make a copy of the input items to avoid modifying the actual input slots
    // directly
    List<ItemStack> input = this.inputSlots.getStacksCopy();

    var validInput = inputCheck(input, ingredients);
    if (!validInput) {
      return false;
    }

    // Now check if there is enough space in the output slots for the result
    ItemStack result = this.result.copy();
    int remainingCount = result.getCount();

    for (int i = 0; i < this.outputSlots.getSlots(); i++) {
      ItemStack outputSlot = this.outputSlots.getStackInSlot(i);

      if (outputSlot.isEmpty()) {
        // If the output slot is empty, it can hold the full remaining count
        remainingCount = 0;
        break;
      } else if (ItemStack.isSameItemSameComponents(outputSlot, result)) {
        // If the output slot contains the same item, calculate the available space
        int spaceAvailable = outputSlot.getMaxStackSize() - outputSlot.getCount();
        remainingCount -= Math.min(remainingCount, spaceAvailable);

        if (remainingCount <= 0) {
          break;
        }
      }
    }

    // If there's still remaining result that can't fit, crafting cannot proceed
    return remainingCount <= 0;
    // If all ingredients were matched and output slots can accommodate the result,
    // return true
  }

  private void craft() {
    // Get the list of ingredients from the recipe
    NonNullList<Ingredient> ingredients = this.recipe.getIngredients();
    // Get the crafting input from the actual input
    CraftingInput input = getCraftingInputFromActualInput(this.craftingInputList);
    // TutorialMod.LOGGER.debug("input: {}", input.items());

    // Get the remaining items
    NonNullList<ItemStack> remainingItems = this.recipe.getRemainingItems(input);

    // Now take the items out of the input
    inputCheck(this.inputSlots.getStacks(), ingredients);

    // Put the result in the output
    ItemStack result = this.result.copy();
    int remainingCount = result.getCount();

    for (int i = 0; i < this.outputSlots.getSlots(); i++) {
      ItemStack outputSlot = this.outputSlots.getStackInSlot(i);

      if (outputSlot.isEmpty()) {
        this.outputSlots.setStackInSlot(i, result);
        remainingCount = 0;
        break;
      } else if (ItemStack.isSameItemSameComponents(outputSlot, result)) {
        int spaceAvailable = outputSlot.getMaxStackSize() - outputSlot.getCount();
        int amountToAdd = Math.min(remainingCount, spaceAvailable);
        outputSlot.grow(amountToAdd);
        remainingCount -= amountToAdd;

        if (remainingCount <= 0) {
          break;
        }
      }
    }

    // Handle remaining items
    remainingCount = 0;
    var toPlaceIn = this.remainItemToggleValue == 0 ? this.inputSlots : this.outputSlots;
    // TutorialMod.LOGGER.debug("toPlaceIn: {}",this.remainItemToggleValue == 1 ? "Input" : "Output" );
    for (ItemStack remainingItem : remainingItems) {
      // TutorialMod.LOGGER.debug("remainingItem: {}", remainingItem);
      remainingCount += remainingItem.getCount();
      if (remainingItem.isEmpty())
        continue;
      for (int j = 0; j < toPlaceIn.getSlots(); j++) {
        ItemStack slot = toPlaceIn.getStackInSlot(j);
        if (ItemStack.isSameItemSameComponents(slot, remainingItem)) {
          int spaceAvailable = slot.getMaxStackSize() - slot.getCount();
          int amountToAdd = Math.min(remainingCount, spaceAvailable);
          slot.grow(amountToAdd);
          remainingCount -= amountToAdd;

          if (remainingCount <= 0) {
            break;
          }
        } else if (slot.isEmpty()) {
          toPlaceIn.setStackInSlot(j, remainingItem.copy());
          remainingCount = 0;
          break;
        }
      }

      setChanged();
    }
  }
  
  private boolean everySecond(double seconds){
    return this.tc % (20 * seconds) == 0;
  }
  
  public RedstoneManager getRedstoneManager() {
    return redstoneManager;
  }
  
  public String getRemainItemToggleDisplayValue() {
    return this.remainItemToggleValue == 0 ? "Input" : "Output";
  }
  
  public void setRemainItemToggleValue(int value) {
    this.remainItemToggleValue = value;
  }
  
  public int toggleRemainItemValue() {
    if (this.remainItemToggleValue == 0) {
      this.remainItemToggleValue = 1;
    } else {
      this.remainItemToggleValue = 0;
    }
    return this.remainItemToggleValue;
  }
  
  public ItemStack getRenderStack() {
    if (this.result == null)
      return ItemStack.EMPTY;
    if (ItemStack.isSameItemSameComponents(this.result, Items.END_CRYSTAL.getDefaultInstance()))
      return ItemStack.EMPTY;
    return this.result;
  }
  
  public void setRenderStack(ItemStack itemStack) {
    this.result = itemStack;
    this.craftingSlots.setStackInSlot(RESULT_SLOT, this.result);
  }

  public @Nullable IItemHandler getCombinedInvWrapper() {
    return this.combinedInvHandler;
  }
  
  public CustomItemStackHandler getInputSlotsItemHandler() {
    return this.inputSlots;
  }
  
  public ItemStackHandler getOutputSlotsItemHandler() {
    return this.outputSlots;
  }
  
  public CraftingSlotHandler getCraftingSlotsItemHandler() {
    return this.craftingSlots;
  }
  
  public SimpleContainer getInputContainer() {
    var t = new SimpleContainer(inputSlots.getSlots());
    for (int i = 0; i < inputSlots.getSlots(); i++) {
      t.setItem(i, inputSlots.getStackInSlot(i));
    }
    return t;
  }
  
  public SimpleContainer getOutputContainer() {
    var t = new SimpleContainer(outputSlots.getSlots());
    for (int i = 0; i < outputSlots.getSlots(); i++) {
      t.setItem(i, outputSlots.getStackInSlot(i));
    }
    return t;
  }

  // Saving and loading
  CompoundTag getTutorialModData(HolderLookup.Provider registries) {
    CompoundTag tutorialModData = new CompoundTag();
    
    // Serialize input, output, and crafting slots
    tutorialModData.put("inputInv", this.inputSlots.serializeNBT(registries));
    tutorialModData.put("outputInv", this.outputSlots.serializeNBT(registries));
    tutorialModData.put("craftingInv", this.craftingSlots.serializeNBT(registries));
    
    // Store additional state variables
    tutorialModData.putInt("remainItemToggleValue", this.remainItemToggleValue);
    tutorialModData.putInt("redstoneMode", this.redstoneManager.getRedstoneMode().ordinal());
    
    // Save the result if it exists
    if (this.result != null && !this.result.isEmpty()) {
      tutorialModData.put("result", this.result.save(registries));
    }
    
    // Attempt to save the recipe, if available
    saveRecipeToNBT(tutorialModData, registries);
    
    return tutorialModData;
  }
  
  private void saveRecipeToNBT(CompoundTag tutorialModData, HolderLookup.Provider registries) {
    try {
      if (this.recipe instanceof CustomRecipeExtender<?> t) {
        tutorialModData.put("recipe", Recipe.CODEC.encodeStart(NbtOps.INSTANCE, t.baseRecipe).getOrThrow());
      }
    } catch (Exception e) {
      TutorialMod.LOGGER.error("Error saving recipe to NBT: {}", e.getMessage());
    }
  }
  
  @Override
  protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
    super.saveAdditional(tag, registries);
    CompoundTag tutorialModData = getTutorialModData(registries);
    tag.put(TutorialMod.MODID, tutorialModData);
  }

  @Override
  protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
    super.loadAdditional(tag, registries);
    
    // Check if we are on the client side
    if (level != null && level.isClientSide()) {
      // Deserialize data from the tag for client-side
      deserializeFromTag(tag, registries);
    } else {
      CompoundTag tutorialModData = tag.getCompound(TutorialMod.MODID);
      deserializeFromTag(tutorialModData, registries);
    }
  }
  
  private void deserializeFromTag(CompoundTag tag, HolderLookup.@NotNull Provider registries) {
    // Deserialize input, output, and crafting slots
    this.inputSlots.deserializeNBT(registries, tag.getCompound("inputInv"));
    this.outputSlots.deserializeNBT(registries, tag.getCompound("outputInv"));
    this.craftingSlots.deserializeNBT(registries, tag.getCompound("craftingInv"));
    
    // Load additional state variables
    this.remainItemToggleValue = tag.getInt("remainItemToggleValue");
    this.redstoneManager.setRedstoneMode(
        REDSTONE_MODE_MAP.get(tag.getInt("redstoneMode"))
    );
    this.result = ItemStack.parseOptional(registries, tag.getCompound("result"));
    
    // Load the recipe if it exists
    if (tag.contains("recipe")) {
      loadRecipeFromNBT(tag.getCompound("recipe"));
    }
  }
  
  private void loadRecipeFromNBT(CompoundTag recipeTag) {
    var recipe = Recipe.CODEC.parse(NbtOps.INSTANCE, recipeTag).getOrThrow();
    if (recipe instanceof CraftingRecipe craftingRecipe) {
      this.recipe = new CustomRecipeExtender<>(craftingRecipe);
    }
  }

  @Override
  public @NotNull CompoundTag getUpdateTag(HolderLookup.@NotNull Provider registries) {
    return getTutorialModData(registries);
  }

  @Override
  public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
    return ClientboundBlockEntityDataPacket.create(this);
  }
  
  // Menu
  @Override
  public @Nullable AbstractContainerMenu createMenu(int containerId, @NotNull Inventory playerInventory,
      @NotNull Player player) {
    return new MechanicalCrafterMenu(containerId, playerInventory, this);
  }
}
